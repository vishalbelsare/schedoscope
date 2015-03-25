package com.ottogroup.bi.soda.bottler

import scala.collection.JavaConversions.asScalaSet
import scala.collection.mutable.ListBuffer
import scala.concurrent.duration.DurationInt
import org.apache.hadoop.conf.Configuration
import com.ottogroup.bi.soda.Settings
import com.ottogroup.bi.soda.bottler.driver.DriverException
import com.ottogroup.bi.soda.dsl.Transformation
import com.ottogroup.bi.soda.dsl.View
import com.ottogroup.bi.soda.dsl.transformations.FilesystemTransformation
import akka.actor.Actor
import akka.actor.ActorRef
import akka.actor.OneForOneStrategy
import akka.actor.Props
import akka.actor.SupervisorStrategy.Escalate
import akka.actor.SupervisorStrategy.Restart
import akka.actor.actorRef2Scala
import akka.contrib.pattern.Aggregator
import akka.event.Logging
import akka.event.LoggingReceive
import java.util.UUID

class ActionStatusRetriever() extends Actor with Aggregator {
  expectOnce {
    case GetActionStatusList(statusRequester, actionQueueStatus, driverActors: Seq[ActorRef]) =>
      if (driverActors.isEmpty) {
        statusRequester ! ActionStatusListResponse(List(), actionQueueStatus)
        context.stop(self)
      } else
        new MultipleResponseHandler(statusRequester, actionQueueStatus, driverActors)

  }

  class MultipleResponseHandler(statusRequester: ActorRef, actionQueueStatus: Map[String, List[String]], driverActors: Iterable[ActorRef]) {
    import context.dispatcher
    import collection.mutable.ArrayBuffer

    val values = ListBuffer[ActionStatusResponse[_]]()

    driverActors.foreach(_ ! GetStatus())
    context.system.scheduler.scheduleOnce(Settings().statusListAggregationTimeout, self, "timeout")

    val handle = expect {
      case actionStatus: ActionStatusResponse[_] => {
        values += actionStatus

        if (values.size == driverActors.size)
          processFinal(values.toList)
      }

      case "timeout" => processFinal(values.toList)
    }

    def processFinal(actionStatus: List[ActionStatusResponse[_]]) {
      unexpect(handle)
      statusRequester ! ActionStatusListResponse(actionStatus, actionQueueStatus)
      context.stop(self)
    }
  }
}

class ActionsManagerActor() extends Actor {
  import context._
  val log = Logging(system, ActionsManagerActor.this)

  val settings = Settings.get(system)

  val availableTransformations = settings.availableTransformations.keySet()

  val nonFilesystemQueues = availableTransformations.filter { _ != "filesystem" }.foldLeft(Map[String, collection.mutable.Queue[CommandWithSender]]()) {
    (nonFilesystemQueuesSoFar, driverName) =>
      nonFilesystemQueuesSoFar + (driverName -> new collection.mutable.Queue[CommandWithSender]())
  }

  val filesystemConcurrency = settings.getDriverSettings("filesystem").concurrency

  val filesystemQueues = (0 until filesystemConcurrency).foldLeft(Map[String, collection.mutable.Queue[CommandWithSender]]()) {
    (filesystemQueuesSoFar, n) => filesystemQueuesSoFar + (s"filesystem-${n}" -> new collection.mutable.Queue[CommandWithSender]())
  }

  val queues = nonFilesystemQueues ++ filesystemQueues

  val randomizer = Random

  def hash(s: String) = Math.max(0,
    s.hashCode().abs % filesystemConcurrency)

  def queueNameForTransformationAction(t: Transformation, s: ActorRef) =
    if (t.name != "filesystem")
      t.name
    else {
      val h = s"filesystem-${hash(s.path.name)}"
      log.debug("computed hash: " + h + " for " + s.path.name)
      h
    }

  def queueNameForTransformationType(transformationType: String) =
    if (transformationType != "filesystem") {
      transformationType
    } else {
      val allFilesystemQueuesEmpty = filesystemQueues.values.foldLeft(true) {
        (emptySoFar, currentQueue) => emptySoFar && currentQueue.isEmpty
      }

      if (allFilesystemQueuesEmpty)
        "filesystem-0"
      else {
        var foundNonEmptyQueue = false
        var randomPick = ""

        while (!foundNonEmptyQueue) {
          randomPick = s"filesystem-${randomizer.nextInt(filesystemConcurrency)}"
          foundNonEmptyQueue = !queues.get(randomPick).isEmpty
        }

        randomPick
      }
    }

  private def actionQueueStatus() = {
    queues.map(q => (q._1, q._2.map(c => {
      if (c.command.isInstanceOf[Transformation])
        c.command.asInstanceOf[Transformation].description
      else
        "deploy"
    }).toList))
  }

  override val supervisorStrategy =
    OneForOneStrategy(maxNrOfRetries = 10, withinTimeRange = 1 minute) {
      case _: DriverException => Restart
      case _: Throwable => Escalate
    }

  override def preStart {
    for (transformation <- availableTransformations; c <- 0 until settings.getDriverSettings(transformation).concurrency) {
      actorOf(DriverActor.props(transformation, self), s"${transformation}-${c + 1}")
    }
  }

  def receive = LoggingReceive({
    case GetStatus() => actorOf(Props[ActionStatusRetriever], "aggregator-" + UUID.randomUUID()) ! GetActionStatusList(sender(), actionQueueStatus(), children.toList.filter { !_.path.toStringWithoutAddress.contains("aggregator-") })

    case PollCommand(transformationType) => {
      val queueForType = queues.get(queueNameForTransformationType(transformationType)).get

      if (!queueForType.isEmpty) {
        val cmd = queueForType.dequeue()
        
        sender ! cmd

        if (cmd.command.isInstanceOf[Transformation]) {
          val transformation = cmd.command.asInstanceOf[Transformation]
          log.info(s"ACTIONMANAGER DEQUEUE: Dequeued ${transformationType} transformation ${transformation}${if (transformation.view.isDefined) s" for view ${transformation.view.get}" else ""}; queue size is now: ${queueForType.size}")
        } else
          log.info("ACTIONMANAGER DEQUEUE: Dequeued deploy action")
      }
    }

    case actionCommand: CommandWithSender => {
      if (actionCommand.command.isInstanceOf[Transformation]) {
        val transformation = actionCommand.command.asInstanceOf[Transformation]        
        val queueName = queueNameForTransformationAction(transformation, actionCommand.sender)

        queues.get(queueName).get.enqueue(actionCommand)
        log.info(s"ACTIONMANAGER ENQUEUE: Enqueued ${queueName} transformation ${transformation}${if (transformation.view.isDefined) s" for view ${transformation.view.get}" else ""}; queue size is now: ${queues.get(queueName).get.size}")
      } else {
        queues.values.foreach { _.enqueue(actionCommand) }
        log.info("ACTIONMANAGER ENQUEUE: Enqueued deploy action")
      }

    }

    case viewAction: View => self ! CommandWithSender(viewAction.transformation().forView(viewAction), sender)

    case filesystemTransformationAction: FilesystemTransformation => self ! CommandWithSender(filesystemTransformationAction, sender)

    case deployAction: Deploy => self ! CommandWithSender(deployAction, sender)
  })
}

object ActionsManagerActor {
  def props(conf: Configuration) = Props[ActionsManagerActor]
}
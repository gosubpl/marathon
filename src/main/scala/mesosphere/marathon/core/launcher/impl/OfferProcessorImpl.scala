package mesosphere.marathon
package core.launcher.impl

import akka.Done
import akka.pattern.AskTimeoutException
import com.typesafe.scalalogging.StrictLogging
import kamon.Kamon
import mesosphere.marathon.core.base.Clock
import mesosphere.marathon.core.instance.update.InstanceUpdateOperation
import mesosphere.marathon.core.launcher.{ InstanceOp, OfferProcessor, OfferProcessorConfig, TaskLauncher }
import mesosphere.marathon.core.matcher.base.OfferMatcher
import mesosphere.marathon.core.matcher.base.OfferMatcher.{ InstanceOpWithSource, MatchedInstanceOps }
import mesosphere.marathon.core.task.tracker.InstanceCreationHandler
import mesosphere.marathon.metrics.{ ServiceMetric, Timer }
import mesosphere.marathon.state.Timestamp
import org.apache.mesos.Protos.{ Offer, OfferID }

import scala.concurrent.Future
import scala.concurrent.duration._
import scala.util.control.NonFatal

/**
  * Passes processed offers to the offerMatcher and launches the appropriate tasks.
  */
private[launcher] class OfferProcessorImpl(
    conf: OfferProcessorConfig, clock: Clock,
    offerMatcher: OfferMatcher,
    taskLauncher: TaskLauncher,
    taskCreationHandler: InstanceCreationHandler) extends OfferProcessor with StrictLogging {
  import scala.concurrent.ExecutionContext.Implicits.global

  private[this] val offerMatchingTimeout = conf.offerMatchingTimeout().millis
  private[this] val saveTasksToLaunchTimeout = conf.saveTasksToLaunchTimeout().millis

  private[this] val incomingOffersMeter =
    Kamon.metrics.minMaxCounter(metrics.name(ServiceMetric, getClass, "incomingOffers"))
  private[this] val matchTimeMeter =
    Timer(metrics.name(ServiceMetric, getClass, "matchTime"))
  private[this] val matchErrorsMeter =
    Kamon.metrics.minMaxCounter(metrics.name(ServiceMetric, getClass, "matchErrors"))
  private[this] val savingTasksTimeMeter =
    Timer(metrics.name(ServiceMetric, getClass, "savingTasks"))
  private[this] val savingTasksTimeoutMeter =
    Kamon.metrics.minMaxCounter(metrics.name(ServiceMetric, getClass, "savingTasksTimeouts"))
  private[this] val savingTasksErrorMeter =
    Kamon.metrics.minMaxCounter(metrics.name(ServiceMetric, getClass, "savingTasksErrors"))

  override def processOffer(offer: Offer): Future[Done] = {
    logger.debug(s"Received offer\n${offer}")
    incomingOffersMeter.increment()

    val matchingDeadline = clock.now() + offerMatchingTimeout
    val savingDeadline = matchingDeadline + saveTasksToLaunchTimeout

    val matchFuture: Future[MatchedInstanceOps] = matchTimeMeter {
      offerMatcher.matchOffer(matchingDeadline, offer)
    }

    matchFuture
      .recover {
        case e: AskTimeoutException =>
          matchErrorsMeter.increment()
          logger.warn(s"Could not process offer '${offer.getId.getValue}' in time. (See --offer_matching_timeout)")
          MatchedInstanceOps(offer.getId, resendThisOffer = true)
        case NonFatal(e) =>
          matchErrorsMeter.increment()
          logger.error(s"Could not process offer '${offer.getId.getValue}'", e)
          MatchedInstanceOps(offer.getId, resendThisOffer = true)
      }.flatMap {
        case MatchedInstanceOps(offerId, tasks, resendThisOffer) =>
          savingTasksTimeMeter {
            saveTasks(tasks, savingDeadline).map { savedTasks =>
              def notAllSaved: Boolean = savedTasks.size != tasks.size
              MatchedInstanceOps(offerId, savedTasks, resendThisOffer || notAllSaved)
            }
          }
      }.flatMap {
        case MatchedInstanceOps(offerId, Nil, resendThisOffer) => declineOffer(offerId, resendThisOffer)
        case MatchedInstanceOps(offerId, tasks, _) => acceptOffer(offerId, tasks)
      }
  }

  private[this] def declineOffer(offerId: OfferID, resendThisOffer: Boolean): Future[Done] = {
    //if the offer should be resent, than we ignore the configured decline offer duration
    val duration: Option[Long] = if (resendThisOffer) None else conf.declineOfferDuration.get
    taskLauncher.declineOffer(offerId, duration)
    Future.successful(Done)
  }

  private[this] def acceptOffer(offerId: OfferID, taskOpsWithSource: Seq[InstanceOpWithSource]): Future[Done] = {
    if (taskLauncher.acceptOffer(offerId, taskOpsWithSource.map(_.op))) {
      logger.debug(s"Offer [${offerId.getValue}]. Task launch successful")
      taskOpsWithSource.foreach(_.accept())
      Future.successful(Done)
    } else {
      logger.warn(s"Offer [${offerId.getValue}]. Task launch rejected")
      taskOpsWithSource.foreach(_.reject("driver unavailable"))
      revertTaskOps(taskOpsWithSource.map(_.op)(collection.breakOut))
    }
  }

  /** Revert the effects of the task ops on the task state. */
  private[this] def revertTaskOps(ops: Seq[InstanceOp]): Future[Done] = {
    val done: Future[Done] = Future.successful(Done)
    ops.foldLeft(done) { (terminatedFuture, nextOp) =>
      terminatedFuture.flatMap { _ =>
        nextOp.oldInstance match {
          case Some(existingInstance) =>
            taskCreationHandler.created(InstanceUpdateOperation.Revert(existingInstance))
          case None =>
            taskCreationHandler.terminated(InstanceUpdateOperation.ForceExpunge(nextOp.instanceId))
        }
      }
    }.recover {
      case NonFatal(e) =>
        throw new RuntimeException("while reverting task ops", e)
    }
  }

  /**
    * Saves the given tasks sequentially, evaluating before each save whether the given deadline has been reached
    * already.
    */
  private[this] def saveTasks(
    ops: Seq[InstanceOpWithSource], savingDeadline: Timestamp): Future[Seq[InstanceOpWithSource]] = {

    def saveTask(taskOpWithSource: InstanceOpWithSource): Future[Option[InstanceOpWithSource]] = {
      val taskId = taskOpWithSource.instanceId
      logger.info(s"Processing ${taskOpWithSource.op.stateOp} for ${taskOpWithSource.instanceId}")
      taskCreationHandler
        .created(taskOpWithSource.op.stateOp)
        .map(_ => Some(taskOpWithSource))
        .recoverWith {
          case NonFatal(e) =>
            savingTasksErrorMeter.increment()
            taskOpWithSource.reject(s"storage error: $e")
            logger.warn(s"error while storing task $taskId for app [${taskId.runSpecId}]", e)
            revertTaskOps(Seq(taskOpWithSource.op))
        }.map { _ => Some(taskOpWithSource) }
    }

    ops.foldLeft(Future.successful(Vector.empty[InstanceOpWithSource])) { (savedTasksFuture, nextTask) =>
      savedTasksFuture.flatMap { savedTasks =>
        if (clock.now() > savingDeadline) {
          savingTasksTimeoutMeter.increment(savedTasks.size.toLong)
          nextTask.reject("saving timeout reached")
          logger.info(
            s"Timeout reached, skipping launch and save for ${nextTask.op.instanceId}. " +
              s"You can reconfigure this with --${conf.saveTasksToLaunchTimeout.name}.")
          Future.successful(savedTasks)
        } else {
          val saveTaskFuture = saveTask(nextTask)
          saveTaskFuture.map(task => savedTasks ++ task)
        }
      }
    }
  }

}

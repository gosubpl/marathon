package mesosphere.marathon
package core.launcher

import mesosphere.marathon.core.base.Clock
import mesosphere.marathon.core.launcher.impl.{ InstanceOpFactoryImpl, OfferProcessorImpl, TaskLauncherImpl }
import mesosphere.marathon.core.matcher.base.OfferMatcher
import mesosphere.marathon.core.plugin.PluginManager
import mesosphere.marathon.core.task.tracker.InstanceCreationHandler

/**
  * This module contains the glue code between matching tasks to resource offers
  * and actually launching the matched tasks.
  */
class LauncherModule(
    conf: MarathonConf,
    taskCreationHandler: InstanceCreationHandler,
    marathonSchedulerDriverHolder: MarathonSchedulerDriverHolder,
    offerMatcher: OfferMatcher,
    pluginManager: PluginManager)(implicit clock: Clock) {

  lazy val offerProcessor: OfferProcessor =
    new OfferProcessorImpl(
      conf, clock,
      offerMatcher, taskLauncher, taskCreationHandler)

  lazy val taskLauncher: TaskLauncher = new TaskLauncherImpl(
    marathonSchedulerDriverHolder)

  lazy val taskOpFactory: InstanceOpFactory = new InstanceOpFactoryImpl(conf, pluginManager)
}

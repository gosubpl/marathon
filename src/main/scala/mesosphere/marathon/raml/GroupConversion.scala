package mesosphere.marathon
package raml

import mesosphere.marathon.core.pod.PodDefinition
import mesosphere.marathon.state.{ AppDefinition, PathId, Timestamp, Group => CoreGroup, VersionInfo => CoreVersionInfo }
import mesosphere.marathon.state.PathId._

trait GroupConversion {

  import GroupConversion._

  // TODO needs a dedicated/focused unit test; other (larger) unit tests provide indirect coverage
  implicit val groupUpdateRamlReads: Reads[(UpdateGroupStructureOp, Context), CoreGroup] =
    Reads[(UpdateGroupStructureOp, Context), CoreGroup] { src =>
      val (op, context) = src
      op.apply(context)
    }

  // intended for TESTING ONLY, see V2TestFormats
  implicit val groupRamlReads: Reads[Group, CoreGroup] = Reads { group =>
    val appsById: Map[AppDefinition.AppKey, AppDefinition] = group.apps.map { ramlApp =>
      val app: AppDefinition = Raml.fromRaml(ramlApp) // assume that we only generate canonical app json
      app.id -> app
    }(collection.breakOut)
    val groupsById: Map[PathId, CoreGroup] = group.groups.map { ramlGroup =>
      val grp: CoreGroup = Raml.fromRaml(ramlGroup)
      grp.id -> grp
    }(collection.breakOut)
    CoreGroup(
      id = group.id.toPath,
      apps = appsById,
      pods = Map.empty[PathId, PodDefinition], // we never read in pods
      groupsById = groupsById,
      dependencies = group.dependencies.map(_.toPath),
      version = group.version.map(Timestamp(_)).getOrElse(Timestamp.now()),
      transitiveAppsById = appsById ++ groupsById.values.flatMap(_.transitiveAppsById),
      transitivePodsById = groupsById.values.flatMap(_.transitivePodsById)(collection.breakOut)
    )
  }
}

object GroupConversion extends GroupConversion {

  case class UpdateGroupStructureOp(
      update: GroupUpdate,
      current: CoreGroup,
      timestamp: Timestamp
  ) {
    import UpdateGroupStructureOp._

    require(update.scaleBy.isEmpty, "For a structural update, no scale should be given.")
    require(update.version.isEmpty, "For a structural update, no version should be given.")

    def apply(implicit ctx: Context): CoreGroup = {
      val effectiveGroups: Map[PathId, CoreGroup] = update.groups.fold(current.groupsById) { updates =>
        updates.map { groupUpdate =>
          val gid = groupId(groupUpdate).canonicalPath(current.id)
          val newGroup = current.groupsById.get(gid).fold(toGroup(groupUpdate, gid, timestamp))(group => UpdateGroupStructureOp(groupUpdate, group, timestamp).apply)
          newGroup.id -> newGroup
        }(collection.breakOut)
      }
      val effectiveApps: Map[AppDefinition.AppKey, AppDefinition] =
        update.apps.map(_.map(ctx.preprocess)).getOrElse(current.apps.values).map { currentApp =>
          val app = toApp(current.id, currentApp, timestamp)
          app.id -> app
        }(collection.breakOut)

      val effectiveDependencies = update.dependencies.fold(current.dependencies)(_.map(PathId(_).canonicalPath(current.id)))
      CoreGroup(
        id = current.id,
        apps = effectiveApps,
        pods = current.pods,
        groupsById = effectiveGroups,
        dependencies = effectiveDependencies,
        version = timestamp,
        transitiveAppsById = effectiveApps ++ effectiveGroups.values.flatMap(_.transitiveAppsById),
        transitivePodsById = current.pods ++ effectiveGroups.values.flatMap(_.transitivePodsById))
    }
  }

  object UpdateGroupStructureOp {

    def groupId(update: GroupUpdate): PathId = update.id.map(PathId(_)).getOrElse(
      // validation should catch this..
      throw new SerializationFailedException("No group id was given!")
    )

    def toApp(gid: PathId, app: AppDefinition, version: Timestamp): AppDefinition = {
      val appId = app.id.canonicalPath(gid)
      app.copy(id = appId, dependencies = app.dependencies.map(_.canonicalPath(gid)),
        versionInfo = CoreVersionInfo.OnlyVersion(version))
    }

    def toGroup(update: GroupUpdate, gid: PathId, version: Timestamp)(implicit ctx: Context): CoreGroup = {
      val appsById: Map[AppDefinition.AppKey, AppDefinition] = update.apps.getOrElse(Set.empty).map { currentApp =>
        val app = toApp(gid, ctx.preprocess(currentApp), version)
        app.id -> app
      }(collection.breakOut)

      val groupsById: Map[PathId, CoreGroup] = update.groups.getOrElse(Seq.empty).map { currentGroup =>
        val group = toGroup(currentGroup, groupId(currentGroup).canonicalPath(gid), version)
        group.id -> group
      }(collection.breakOut)

      CoreGroup(
        id = gid,
        apps = appsById,
        pods = Map.empty,
        groupsById = groupsById,
        dependencies = update.dependencies.fold(Set.empty[PathId])(_.map(PathId(_).canonicalPath(gid))),
        version = version,
        transitiveAppsById = appsById ++ groupsById.values.flatMap(_.transitiveAppsById),
        transitivePodsById = Map.empty)
    }
  }

  trait Context {
    def preprocess(app: App): AppDefinition
  }
}
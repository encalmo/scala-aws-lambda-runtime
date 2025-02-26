#!/usr/bin/env -S scala-cli shebang --quiet

//----------------------------------------
// Computes new semantic version from
// the git tags having a given prefix
//----------------------------------------

//> using scala 3.5.2
//> using jvm 21
//> using toolkit 0.7.0

val tagPrefix = getArg("--prefix", "v")
val bumpMethod = getArg("--bump", "major")

val gittags = os.proc("git", "tag").call()

assert(gittags.exitCode == 0)

val currentVersion: Option[Semver] =
  gittags.out
    .text()
    .linesIterator
    .filter(tag => tag.startsWith(tagPrefix))
    .filter(tag => !tag.endsWith("-SNAPSHOT"))
    .map(Semver.parse)
    .toSeq
    .sorted
    .lastOption

val newVersion: Semver =
  bumpMethod
    .match {
      case "keep" | "none" => currentVersion
      case "major"         => currentVersion.map(_.bumpMajor())
      case "minor"         => currentVersion.map(_.bumpMinor())
      case _               => currentVersion.map(_.bumpPatch())
    }
    .getOrElse(Semver.initialVersion)

println(s"new_version=$newVersion")

// ---- UTILS ----

case class Semver(
    major: Int,
    minor: Option[Int],
    patch: Option[Int],
    suffix: Option[String]
) {

  def bumpMajor() =
    copy(
      major = major + 1,
      minor = minor.map(_ => 0),
      patch = patch.map(_ => 0),
      suffix = None
    )

  def bumpMinor() =
    copy(minor = minor.map(_ + 1), patch = patch.map(_ => 0), suffix = None)

  def bumpPatch() =
    copy(patch = patch.map(_ + 1), suffix = None)

  override val toString: String = {
    val b = new StringBuilder()
    b.append(major)
    minor.foreach { m =>
      b.append('.')
      b.append(m)
      patch.foreach { p =>
        b.append('.')
        b.append(p)
      }
    }
    suffix.foreach { s =>
      b.append(s)
    }
    b.toString()
  }

}

given scala.math.Ordering[Semver] =
  Ordering.by(s => (s.major, s.minor, s.patch))

object Semver {

  val initialVersion = Semver(0, Some(9), Some(0), None)

  val semverRegex = """(\d+)\.?(\d+)?\.?(\d+)?-?(.+)?""".r

  def parse(tag: String): Semver =
    tag.dropWhile(c => !c.isDigit).match { case semverRegex(major, minor, patch, suffix) =>
      Semver(
        major.toInt,
        Option(minor).map(_.toInt),
        Option(patch).map(_.toInt),
        Option(suffix)
      )
    }
}

def getArg(key: String, defaultValue: String) =
  val prefix = s"$key="
  args
    .filter(_.startsWith(prefix))
    .headOption
    .map(_.drop(prefix.length()))
    .getOrElse(defaultValue)

Global / onChangedBuildSource := ReloadOnSourceChanges
import scalajsbundler.util.JSON._

inThisBuild(
  Seq(
    organization           := "io.github.fun-stack",
    scalaVersion           := "2.13.8",
    licenses               := Seq("MIT License" -> url("https://opensource.org/licenses/MIT")),
    homepage               := Some(url("https://github.com/cornerman/talpini")),
    scmInfo                := Some(
      ScmInfo(
        url("https://github.com/cornerman/talpini"),
        "scm:git:git@github.com:cornerman/talpini.git",
        Some("scm:git:git@github.com:cornerman/talpini.git"),
      ),
    ),
    pomExtra               :=
      <developers>
      <developer>
        <id>jkaroff</id>
        <name>Johannes Karoff</name>
        <url>https://github.com/cornerman</url>
      </developer>
    </developers>,
    sonatypeCredentialHost := "s01.oss.sonatype.org",
    sonatypeRepository     := "https://s01.oss.sonatype.org/service/local",
  ),
)

lazy val commonSettings = Seq(
  addCompilerPlugin("org.typelevel" % "kind-projector" % "0.13.2" cross CrossVersion.full),
  libraryDependencies  ++=
    Deps.scalatest.value % Test ::
      Nil,
  scalacOptions --= Seq("-Xfatal-warnings"),
)

lazy val cli = project
  .in(file("cli"))
  .enablePlugins(ScalaJSPlugin, ScalaJSBundlerPlugin, ScalablyTypedConverterPlugin)
  .settings(commonSettings)
  .settings(
    name                            := "talpini",
    useYarn                         := true,
    scalaJSUseMainModuleInitializer := true,
    webpackConfigFile               := Some(baseDirectory.value / "webpack.config.js"),
    libraryDependencies            ++= List(
      Deps.cats.core.value,
      Deps.cats.effect.value,
      Deps.scalaJS.secureRandom.value,
      Deps.pprint.value,
    ),
    stIgnore                       ++= List(
      "aws-sdk",
    ),
    Compile / npmDependencies      ++= Seq(
      "glob"                              -> "8.0.3",
      "@types/glob"                       -> "7.2.0",
      "colors"                            -> "1.4.0",
      "@aws-sdk/client-s3"                -> "3.110.0",
      "@aws-sdk/client-dynamodb"          -> "3.110.0",
      "@aws-sdk/client-sts"               -> "3.110.0",
      "js-yaml"                           -> "4.1.0",
      "@types/js-yaml"                    -> "4.0.5",
      "@types/node"                       -> "16.11.7",
      "@aws-sdk/credential-provider-node" -> "github:cornerman/aws-sdk-credential-provider-node.git#semver:3.110.0",
      "@aws-sdk/credential-provider-ini"  -> "github:cornerman/aws-sdk-credential-provider-ini.git#semver:3.110.0",
      "aws-sdk"                           -> "2.892.0",
    ),
    Compile / npmDevDependencies   ++= Seq(
    ),
    // TODO: workaround for https://github.com/aws/aws-sdk-js-v3/issues/3505#issuecomment-1154139931
    Compile / additionalNpmConfig   := Map(
      "version"   -> str(version.value),
      "overrides" -> obj(
        "@aws-sdk/client-s3"       -> obj(
          "@aws-sdk/credential-provider-node" -> str("github:cornerman/aws-sdk-credential-provider-node.git#semver:3.110.0"),
        ),
        "@aws-sdk/client-dynamodb" -> obj(
          "@aws-sdk/credential-provider-node" -> str("github:cornerman/aws-sdk-credential-provider-node.git#semver:3.110.0"),
        ),
        "@aws-sdk/client-sts"      -> obj(
          "@aws-sdk/credential-provider-node" -> str("github:cornerman/aws-sdk-credential-provider-node.git#semver:3.110.0"),
        ),
      ),
    ),
  )

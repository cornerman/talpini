package terraverse.config

import cats.data.EitherNec
import cats.implicits._
import terraverse.AppConfig
import terraverse.native.JsNative
import typings.node.cryptoMod.BinaryToTextEncoding
import typings.node.{cryptoMod, pathMod}

import scala.scalajs.js
import scala.scalajs.js.annotation.JSName

case class LoadedConfigType[T <: js.Any](filePath: String, rootPath: String, config: T) {
  val filePathRelative      = pathMod.relative(".", filePath)
  val dirPath               = pathMod.dirname(filePath)
  val dirPathRelative       = pathMod.dirname(dirPath)
  val name                  = pathMod.basename(filePath).replaceAll("\\..*$", "")
  val nameRelative          = pathMod.relative(".", pathMod.join(dirPath, name))
  val terraformPath         = pathMod.resolve(dirPath, ".terraverse", name)
  val terraformPathRelative = pathMod.relative(".", terraformPath)

  def digest: String = {
    val input = s"${AppConfig.version}|${js.JSON.stringify(config)}"
    cryptoMod.createHash("sha256").update(input).digest(BinaryToTextEncoding.hex)
  }
}

trait ConfigRaw extends js.Object {
  def includes: js.UndefOr[js.Array[String]]
  def dependencies: js.UndefOr[js.Dictionary[String]]
  def values: js.Any
}
object ConfigRaw {
  def parse(any: js.Any): EitherNec[String, ConfigRaw] = {
    val validate = Validate(any)

    val validation =
      validate.field("includes")(m => !JsNative.isDefined(m) || JsNative.isArray(m)) &>
        validate.field("dependencies")(m => !JsNative.isDefined(m) || JsNative.isObject(m))

    validation.map(_ => any.asInstanceOf[ConfigRaw])
  }
}

trait Config extends js.Object {
  @JSName("enabled") def _enabled: js.UndefOr[Boolean]
  @JSName("sensitive") def _sensitive: js.UndefOr[Boolean]
  @JSName("generate") def _generateFiles: js.UndefOr[js.Dictionary[String]]
  @JSName("copy") def _copyFiles: js.UndefOr[js.Array[String]]
  @JSName("dependencies") def _dependencies: js.UndefOr[js.Dictionary[String]]
  def backend: js.UndefOr[ConfigBackend]
  @JSName("module") def _module: js.UndefOr[js.Dictionary[js.Dictionary[js.Any]]]
  @JSName("providers") def _providers: js.UndefOr[js.Dictionary[js.Array[js.Dictionary[js.Any]]]]
  // TODO: resource? data? multi-module?
}
object Config {
  implicit class Ops(private val self: Config) extends AnyVal {
    def sensitive     = self._enabled.getOrElse(false)
    def enabled       = self._enabled.getOrElse(true)
    def generateFiles = self._generateFiles.getOrElse(js.Dictionary.empty)
    def copyFiles     = self._copyFiles.getOrElse(js.Array())
    def dependencies  = self._dependencies.getOrElse(js.Dictionary.empty)
    def providers     = self._providers.getOrElse(js.Dictionary.empty)
    def module        = self._module.getOrElse(js.Dictionary.empty)
  }

  def parse(any: js.Any): EitherNec[String, Config] = {
    val validate = Validate(any)

    val validation =
      validate.field("enabled")(m => !JsNative.isDefined(m) || JsNative.isBoolean(m)) &>
        validate.field("generate")(m => !JsNative.isDefined(m) || JsNative.isObject(m)) &>
        validate.field("copy")(m => !JsNative.isDefined(m) || JsNative.isArray(m)) &>
        validate.field("dependencies")(m => !JsNative.isDefined(m) || JsNative.isObject(m)) &>
        validate.fieldFlat("terraform.backend")(m => if (JsNative.isDefined(m)) ConfigBackend.parse(m) else Either.unit) &>
        validate.field("terraform.module")(m => !JsNative.isDefined(m) || JsNative.isObject(m)) &>
        validate.field("terraform.providers")(m => !JsNative.isDefined(m) || JsNative.isObject(m))

    validation.map(_ => any.asInstanceOf[Config])
  }
}

sealed trait ConfigBackend extends js.Object {
  @JSName("type") def tpe: String
}
object ConfigBackend {
  implicit class Ops(private val self: ConfigBackend) extends AnyVal {
    def config: js.Dictionary[js.Any] = self.asInstanceOf[js.Dictionary[js.Any]].filter(_._1 != "type")
  }

  sealed trait Unknown extends ConfigBackend
  object Unknown {
    def parse(any: js.Any): EitherNec[String, ConfigBackend.Unknown] = {
      val validate = Validate(any)

      val validation =
        validate.field("type")(m => JsNative.isDefined(m) && JsNative.isString(m))

      validation.map(_ => any.asInstanceOf[ConfigBackend.Unknown])
    }
  }
  sealed trait Local extends ConfigBackend {
    def path: js.UndefOr[String]
    def workspace_dir: js.UndefOr[String]
  }
  object Local   {
    val tpeName: String = "local"

    def parse(any: js.Any): EitherNec[String, Local] = {
      val validate = Validate(any)

      val validation =
        validate.field("type")(m => JsNative.isDefined(m) && JsNative.isString(m) && m == (tpeName: js.Any)) &>
          validate.field("path")(m => JsNative.isDefined(m) && JsNative.isString(m)) &>
          validate.field("workspace_dir")(m => !JsNative.isDefined(m) || JsNative.isString(m))

      validation.map(_ => any.asInstanceOf[ConfigBackend.Local])
    }
  }
  // incomplete, only what is needed from https://www.terraform.io/language/settings/backends/s3
  sealed trait S3 extends ConfigBackend {
    def bucket: String
    def region: js.UndefOr[String]
    def acl: js.UndefOr[String]
    @JSName("force_path_style") def forcePathStyle: js.UndefOr[Boolean]
    def endpoint: js.UndefOr[String]
    @JSName("dynamodb_table") def dynamodbTable: js.UndefOr[String]
    @JSName("dynamodb_endpoint") def dynamodbEndpoint: js.UndefOr[String]
    @JSName("iam_endpoint") def iamEndpoint: js.UndefOr[String]
    @JSName("max_retries") def maxRetries: js.UndefOr[Int]
    @JSName("access_key") def accessKey: js.UndefOr[String]
    @JSName("secret_key") def secretKey: js.UndefOr[String]
    def profile: js.UndefOr[String]
    @JSName("shared_credentials_file") def sharedCredentialsFile: js.UndefOr[String]
    @JSName("sts_endpoint") def stsEndpoint: js.UndefOr[String]
    def token: js.UndefOr[String]
    @JSName("assume_role_duration_seconds") def assumeRoleDurationSeconds: js.UndefOr[Double]
    @JSName("assume_role_policy") def assumeRolePolicy: js.UndefOr[String]
    @JSName("external_id") def externalId: js.UndefOr[String]
    @JSName("role_arn") def roleArn: js.UndefOr[String]
    @JSName("session_name") def sessionName: js.UndefOr[String]
    @JSName("assumeRolePolicyArns") def _assumeRolePolicyArns: js.UndefOr[js.Array[String]]
    @JSName("assumeRoleTags") def _assumeRoleTags: js.UndefOr[js.Dictionary[String]]
    @JSName("assumeRoleTransitiveTagKeys") def _assumeRoleTransitiveTagKeys: js.UndefOr[js.Array[String]]
  }
  object S3      {
    implicit class Ops(private val self: S3) extends AnyVal {
      def assumeRolePolicyArns        = self._assumeRolePolicyArns.getOrElse(js.Array())
      def assumeRoleTags              = self._assumeRoleTags.getOrElse(js.Dictionary.empty)
      def assumeRoleTransitiveTagKeys = self._assumeRoleTransitiveTagKeys.getOrElse(js.Array())
    }

    val tpeName: String = "s3"

    def parse(any: js.Any): EitherNec[String, S3] = {
      val validate = Validate(any)

      val validation =
        validate.field("type")(m => JsNative.isDefined(m) && JsNative.isString(m) && m == (tpeName: js.Any)) &>
          validate.field("bucket")(m => JsNative.isDefined(m) && JsNative.isString(m)) &>
          validate.field("region")(m => !JsNative.isDefined(m) || JsNative.isString(m)) &>
          validate.field("acl")(m => !JsNative.isDefined(m) || JsNative.isString(m)) &>
          validate.field("force_path_style")(m => !JsNative.isDefined(m) || JsNative.isBoolean(m)) &>
          validate.field("endpoint")(m => !JsNative.isDefined(m) || JsNative.isString(m)) &>
          validate.field("dynamodb_table")(m => !JsNative.isDefined(m) || JsNative.isString(m)) &>
          validate.field("dynamodb_endpoint")(m => !JsNative.isDefined(m) || JsNative.isString(m)) &>
          validate.field("iam_endpoint")(m => !JsNative.isDefined(m) || JsNative.isString(m)) &>
          validate.field("max_retries")(m => !JsNative.isDefined(m) || JsNative.isNumber(m)) &>
          validate.field("access_key")(m => !JsNative.isDefined(m) || JsNative.isString(m)) &>
          validate.field("secret_key")(m => !JsNative.isDefined(m) || JsNative.isString(m)) &>
          validate.field("profile")(m => !JsNative.isDefined(m) || JsNative.isString(m)) &>
          validate.field("shared_credentials_file")(m => !JsNative.isDefined(m) || JsNative.isString(m)) &>
          validate.field("sts_endpoint")(m => !JsNative.isDefined(m) || JsNative.isString(m)) &>
          validate.field("token")(m => !JsNative.isDefined(m) || JsNative.isString(m)) &>
          validate.field("assume_roleDurationSeconds")(m => !JsNative.isDefined(m) || JsNative.isNumber(m)) &>
          validate.field("assume_role_policy")(m => !JsNative.isDefined(m) || JsNative.isString(m)) &>
          validate.field("assume_role_policy_arns")(m => !JsNative.isDefined(m) || JsNative.isArray(m)) &>
          validate.field("assume_role_tags")(m => !JsNative.isDefined(m) || JsNative.isObject(m)) &>
          validate.field("assume_role_transitive_tag_keys")(m => !JsNative.isDefined(m) || JsNative.isArray(m)) &>
          validate.field("external_id")(m => !JsNative.isDefined(m) || JsNative.isString(m)) &>
          validate.field("role_arn")(m => !JsNative.isDefined(m) || JsNative.isString(m)) &>
          validate.field("session_name")(m => !JsNative.isDefined(m) || JsNative.isString(m))

      validation.map(_ => any.asInstanceOf[ConfigBackend.S3])
    }
  }

  def parse(any: js.Any): EitherNec[String, ConfigBackend] =
    for {
      unknown <- ConfigBackend.Unknown.parse(any)
      result  <- unknown.tpe match {
                   case ConfigBackend.S3.tpeName    => ConfigBackend.S3.parse(any)
                   case ConfigBackend.Local.tpeName => ConfigBackend.Local.parse(any)
                   case _                           => unknown.rightNec
                 }
    } yield result
}

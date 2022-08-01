package talpini.cloud.aws

import cats.effect.IO
import cats.implicits._
import org.scalablytyped.runtime.StObject
import talpini.cli.UserPrompt
import talpini.config.ConfigBackend
import talpini.logging.Logger
import typings.awsSdkClientDynamodb.dynamoDBClientMod.DynamoDBClientConfig
import typings.awsSdkClientDynamodb.mod.{CreateTableCommand, DescribeTableCommand, DynamoDBClient}
import typings.awsSdkClientDynamodb.models0Mod._
import typings.awsSdkClientS3.mod._
import typings.awsSdkClientS3.models0Mod.{CreateBucketRequest, HeadBucketRequest, PublicAccessBlockConfiguration, PutBucketVersioningRequest, VersioningConfiguration, _}
import typings.awsSdkClientS3.models1Mod.PutPublicAccessBlockRequest
import typings.awsSdkClientS3.s3ClientMod.S3ClientConfig
import typings.awsSdkClientSts.assumeRoleCommandMod.{AssumeRoleCommandInput, AssumeRoleCommandOutput}
import typings.awsSdkClientSts.mod._
import typings.awsSdkClientSts.models0Mod.{GetCallerIdentityRequest, PolicyDescriptorType, Tag}
import typings.awsSdkClientSts.stsclientMod.STSClientConfig
import typings.awsSdkCredentialProviderIni.resolveAssumeRoleCredentialsMod.AssumeRoleParams
import typings.awsSdkCredentialProviderNode.defaultProviderMod.DefaultProviderInit
import typings.awsSdkCredentialProviderNode.mod.defaultProvider
import typings.awsSdkCredentialProviderWebIdentity.fromWebTokenMod.AssumeRoleWithWebIdentityParams
import typings.awsSdkTypes.clientMod.Client
import typings.awsSdkTypes.commandMod.Command
import typings.awsSdkTypes.credentialsMod.{CredentialProvider, Credentials}
import typings.awsSdkTypes.responseMod.MetadataBearer
import typings.colors.{safeMod => Colors}

import scala.scalajs.js
import scala.scalajs.js.JSConverters.JSRichIterableOnce
import scala.scalajs.js.|

private object Extensions {
  implicit class ClientOps(val client: Client[_, _, _]) extends AnyVal {
    // TODO: type signature of normal send broken
    def sendJS[Output <: MetadataBearer](message: String)(command: Command[_, _, _, Output, _]): js.Promise[Output] =
      client
        .asInstanceOf[js.Dynamic]
        .send(command)
        .`then`(
          { (result: Output) =>
            Logger.trace(s"${message}. Status Code: ${result.$metadata.httpStatusCode}")
            result
          },
          { (error: js.Any) =>
            Logger.trace(s"${message}. Error: ${error}")
            error
          },
        )
        .asInstanceOf[js.Promise[Output]]

    def sendIO[Output <: MetadataBearer](message: String)(command: Command[_, _, _, Output, _]): IO[Output] =
      IO.fromPromise(IO(sendJS(message)(command)))
  }

  // add sugar for set interface of scalablytyped to apply options...
  implicit class SetterSugar[T <: StObject](private val self: T) extends AnyVal {
    def opt[A](option: js.UndefOr[A])(f: (T, A) => T): T = option.fold(self)(a => f(self, a))
    def opt[A](option: Option[A])(f: (T, A) => T): T     = option.fold(self)(a => f(self, a))
  }
}
import talpini.cloud.aws.Extensions._

object AWS {
  def stateCredentials(backend: ConfigBackend.S3): CredentialProvider = {

    def keyCredentials: Option[CredentialProvider] =
      (backend.accessKey.toOption, backend.secretKey.toOption).mapN(Credentials(_, _)).map(c => () => js.Promise.resolve[Credentials](c))

    def defaultCredentials: CredentialProvider = () =>
      defaultProvider(
        DefaultProviderInit()
          .setRoleAssumer(
            getDefaultRoleAssumer().asInstanceOf[js.Function2[Credentials, AssumeRoleParams, js.Promise[Credentials]]],
          )
          .setRoleAssumerWithWebIdentity(
            getDefaultRoleAssumerWithWebIdentity().asInstanceOf[js.Function1[AssumeRoleWithWebIdentityParams, js.Promise[Credentials]]],
          )
          .opt(backend.sharedCredentialsFile)(_ setConfigFilepath _)
          .opt(backend.profile)(_ setProfile _)
          .opt(backend.maxRetries.map(_.toDouble))(_ setMaxRetries _)
          .opt(backend.token.map(t => (_: String) => js.Promise.resolve[String](t)))(_ setMfaCodeProvider _),
      )(())

    val baseCredentials: CredentialProvider = keyCredentials.getOrElse(defaultCredentials)

    val assumeRoleCredentials: Option[CredentialProvider] = backend.roleArn.toOption.map { roleArn =>
      val stsConfig = js
        .Object()
        .asInstanceOf[STSClientConfig]
        .opt(backend.region)(_ setRegion _)
        .opt(backend.stsEndpoint)(_ setEndpoint _)
        .setCredentials(baseCredentials)

      val stsClient = new STSClient(stsConfig)

      val command = new AssumeRoleCommand(
        js
          .Object()
          .asInstanceOf[AssumeRoleCommandInput]
          .setRoleArn(roleArn)
          .setRoleSessionName(backend.sessionName.getOrElse("talpini"))
          .opt(backend.assumeRolePolicy)(_ setPolicy _)
          .opt(backend.assumeRoleDurationSeconds)(_ setDurationSeconds _)
          .opt(backend.externalId)(_ setExternalId _)
          .opt(backend.assumeRolePolicy)(_ setPolicy _)
          .setTransitiveTagKeys(backend.assumeRoleTransitiveTagKeys)
          .setTags(backend.assumeRoleTags.map { case (k, v) => Tag().setKey(k).setValue(v) }.toJSArray)
          .setPolicyArns(backend.assumeRolePolicyArns.map(s => PolicyDescriptorType().setArn(s))),
      )

      () =>
        stsClient
          .sendJS(s"Assume backend role ${roleArn}")(command)
          .asInstanceOf[js.Dynamic] // TODO: signature of then is totally broken
          .`then` { (x: AssumeRoleCommandOutput) =>
            val newCredentials = for {
              credentials  <- x.Credentials
              accessKeyId  <- credentials.AccessKeyId
              secretKeyId  <- credentials.SecretAccessKey
              sessionToken <- credentials.SessionToken
              expiration   <- credentials.Expiration
            } yield Credentials(accessKeyId, secretKeyId).setSessionToken(sessionToken).setExpiration(expiration)

            newCredentials
              .fold[js.Promise[Credentials]](js.Promise.reject("Failed to assume role, no credentials"))(js.Promise.resolve[Credentials](_))
          }
          .asInstanceOf[js.Promise[Credentials]]
    }

    assumeRoleCredentials.getOrElse(baseCredentials)
  }

  def createS3State(backend: ConfigBackend.S3): IO[Unit] = IO.fromPromise(IO(stateCredentials(backend)())).flatMap { credentials =>
    lazy val s3Client = new S3Client(
      js.Object()
        .asInstanceOf[S3ClientConfig]
        .setCredentials(credentials)
        .opt(backend.region)(_ setRegion _)
        .opt(backend.endpoint)(_ setEndpoint _)
        .opt(backend.forcePathStyle)(_ setForcePathStyle _),
    )

    lazy val dynamoDBClient = new DynamoDBClient(
      js.Object()
        .asInstanceOf[DynamoDBClientConfig]
        .setCredentials(credentials)
        .opt(backend.region)(_ setRegion _)
        .opt(backend.dynamodbEndpoint)(_ setEndpoint _),
    )

    lazy val stsClient = new STSClient(
      js.Object()
        .asInstanceOf[STSClientConfig]
        .setCredentials(credentials)
        .opt(backend.region)(_ setRegion _)
        .opt(backend.stsEndpoint)(_ setEndpoint _),
    )

    def headBucketCommand = new HeadBucketCommand(
      HeadBucketRequest()
        .setBucket(backend.bucket),
    )

    def describeTableCommand(table: String) = new DescribeTableCommand(
      DescribeTableInput()
        .setTableName(table),
    )

    def createBucketCommand = new CreateBucketCommand(
      CreateBucketRequest()
        .setBucket(backend.bucket)
        .opt(backend.acl)(_ setACL _),
    )

    def putBucketVersioningCommand = new PutBucketVersioningCommand(
      PutBucketVersioningRequest()
        .setBucket(backend.bucket)
        .setVersioningConfiguration(VersioningConfiguration().setStatus(BucketVersioningStatus.Enabled)),
    )

    def putPublicAccessBlockCommand = new PutPublicAccessBlockCommand(
      PutPublicAccessBlockRequest()
        .setBucket(backend.bucket)
        .setPublicAccessBlockConfiguration(
          PublicAccessBlockConfiguration()
            .setBlockPublicAcls(true)
            .setBlockPublicPolicy(true)
            .setIgnorePublicAcls(true)
            .setRestrictPublicBuckets(true),
        ),
    )

    def createTableCommand(table: String) = new CreateTableCommand(
      CreateTableInput()
        .setTableName(table)
        .setBillingMode(BillingMode.PAY_PER_REQUEST)
        .setAttributeDefinitionsVarargs(
          AttributeDefinition().setAttributeName("LockID").setAttributeType(ScalarAttributeType.S),
        )
        .setKeySchemaVarargs(
          KeySchemaElement().setAttributeName("LockID").setKeyType(KeyType.HASH),
        ),
    )

    val modifyBucket =
      s3Client.sendIO(s"Put s3 bucket versioning on bucket ${backend.bucket}")(putBucketVersioningCommand).void &>
        s3Client.sendIO(s"Put public access block on bucket ${backend.bucket}")(putPublicAccessBlockCommand).void

    val createBucket =
      s3Client.sendIO(s"Create s3 bucket ${backend.bucket}")(createBucketCommand).void *>
        modifyBucket

    val createTable = backend.dynamodbTable.toOption.traverse_(table => dynamoDBClient.sendIO(s"Create dynamodb table ${table}")(createTableCommand(table)))

    val bucketExists = s3Client
      .sendIO(s"Check s3 bucket exists ${backend.bucket}")(headBucketCommand)
      .attempt
      .map(_.exists(_.$metadata.httpStatusCode == (200: Unit | Double)))

    val tableExists = backend.dynamodbTable.toOption
      .traverse(table => dynamoDBClient.sendIO(s"Check dynamodb table exists ${table}")(describeTableCommand(table)))
      .attempt
      .map(_.exists(_.exists(_.$metadata.httpStatusCode == (200: Unit | Double))))

    val callerIdentity = stsClient.sendIO("Get aws caller identity")(new GetCallerIdentityCommand(js.Object().asInstanceOf[GetCallerIdentityRequest]))

    (bucketExists, tableExists, callerIdentity).parTupled.flatMap { case (bucketExists, tableExists, callerIdentity) =>
      val userConfirm = UserPrompt.confirmIf(!bucketExists || !tableExists)(
        s"""
           |Your configured s3 backend does not exist yet inside the aws account '${callerIdentity.Account}'.
           |
           |Bucket: '${backend.bucket}'
           |Table: ${backend.dynamodbTable.fold("-")(t => s"'$t'")}
           |
           |${Colors.red("Should talpini create it for you?")}""".stripMargin,
      )

      userConfirm match {
        case true  => createBucket.unlessA(bucketExists).void &> createTable.unlessA(tableExists).void
        case false => IO.unit
      }
    }
  }
}

<a href="https://github.com/encalmo/scala-aws-lambda-runtime">![GitHub](https://img.shields.io/badge/github-%23121011.svg?style=for-the-badge&logo=github&logoColor=white)</a> <a href="https://central.sonatype.com/artifact/org.encalmo/scala-aws-lambda-runtime_3" target="_blank">![Maven Central Version](https://img.shields.io/maven-central/v/org.encalmo/scala-aws-lambda-runtime_3?style=for-the-badge)</a> <a href="https://encalmo.github.io/scala-aws-lambda-runtime/scaladoc/org/encalmo/lambda.html" target="_blank"><img alt="Scaladoc" src="https://img.shields.io/badge/docs-scaladoc-red?style=for-the-badge"></a>

# scala-aws-lambda-runtime

This Scala3 library provides a custom [AWS Lambda](https://aws.amazon.com/pm/lambda) runtime for building functions using Scala3.

Read more about AWS Lambda: 
- https://docs.aws.amazon.com/lambda/latest/dg/runtimes-custom.html
- https://docs.aws.amazon.com/lambda/latest/dg/runtimes-api.html
- https://docs.aws.amazon.com/lambda/latest/dg/runtimes-walkthrough.html

## Table of contents

- [Dependencies](#dependencies)
- [Usage](#usage)
- [Handler API](#handler-api)
   - [initialize](#initialize)
   - [handleRequest](#handlerequest)
   - [ApplicationContext](#applicationcontext)
   - [LambdaContext](#lambdacontext)
   - [LambdaEnvironment](#lambdaenvironment)
- [Main method](#main-method)
- [Custom runtime lifecycle](#custom-runtime-lifecycle)
- [Lambda template g8](#lambda-template-g8)
- [Examples](#examples)
   - [The simplest lambda example](#the-simplest-lambda-example)
   - [Example of lambda with initialized ApplicationContext](#example-of-lambda-with-initialized-applicationcontext)
   - [Example of a lambda with AwsClient setup and reading of managed secrets](#example-of-a-lambda-with-awsclient-setup-and-reading-of-managed-secrets)
- [Project content](#project-content)

## Dependencies

   - [Scala](https://www.scala-lang.org) >= 3.3.5
   - com.amazonaws [**aws-lambda-java-core** 1.2.3](https://central.sonatype.com/artifact/com.amazonaws/aws-lambda-java-core)

## Usage

Use with SBT

    libraryDependencies += "org.encalmo" %% "scala-aws-lambda-runtime" % "0.9.11"

or with SCALA-CLI

    //> using dep org.encalmo::scala-aws-lambda-runtime:0.9.11

## Handler API

The contract for lambda handler is defined in the [EventHandler](https://github.com/encalmo/scala-aws-lambda-runtime/blob/main/EventHandler.scala) trait  as:

```scala
  /** Custom context initializez by the application. */
  type ApplicationContext

  /** Initialize your implicit ApplicationContext here based on the lambda environment.
    *
    * This context can be anything you want to initialize ONCE per lambda run, e.g. AWS client, etc.
    */
  def initialize(using LambdaEnvironment): ApplicationContext

  /** Provide your lambda business logic here.
    *
    * @param input
    *   event sent to the lambda
    * @return
    *   lambda output string
    */
  def handleRequest(input: String)(using LambdaContext, ApplicationContext): String
```

### initialize

  The `initialize` method is invoked only **once** per lambda execution environment and produces an instance of the *ApplicationContext* type. This value is later passed to each invocation of the `handleRequest`. This is your **dependecy injection** moment.

### handleRequest

  The `handlerRequest` is the method executed on each lambda invocation. It takes an input string representing lambda event, and returns a string passed back to lambda caller. This is accompanied by two implicit arguments: *LambdaContext* and *ApplicationContext*.

### ApplicationContext

  Abstract type *ApplicationContext* represents anything you want to initialize only **once** and re-use between request handler invocations. This can be a type alias, a case class, a tuple, a named tuple or a map, you name it:

  ```scala
  case class Config(greeting: String)
  type ApplicationContext = Config
  ```
  or
  ```scala
  type ApplicationContext = (Config, AwsClient)
  ```
  or
  ```scala
  type ApplicationContext = (config: Config, awsClient: AwsClient)
  ```
  in case application context is not needed one can declare always
  ```scala
  type ApplicationContext = Unit
  ```

### LambdaContext

  *LambdaContext* class provides access to both *LambdaEnvironment* instance and current lambda invocation properties.

### LambdaEnvironment

  *LambdaEnvironment* class represents properties of the lambda execution environment and custom runtime. Since those properties might be simulated in the tests and in the local run, it is recommended to use those methods over reading from system variable's directly.


## Main method

Each lambda is compiled into a standalone application binary using GraalVM. The entry point is a main method defined on the lambda's companion object, e.g. 

```scala
object ExampleLambda {
  @static def main(args: Array[String]): Unit = new ExampleLambda().run()
}
```

The name of the main class must be declared in your build for graalvm to work properly, e.g.
```
//> using mainClass org.encalmo.lambda.example.ExampleLambda
```

## Custom runtime lifecycle

Custom runtime embeded in the *LambdaRuntime* trait does NOT start immediately when lambda instance is initialized. 
Instead, runtime instance must be initialized explicitly by invoking `run()` method. This design allows us to test lambda without http overhead, or to even run lambda using other runtimes (like built-in AWS java runtimes).

Under the cover we run three other methods:
```scala
def run = 
  initializeLambdaRuntime()
  .start()
  .waitUntilInterrupted()
```

- `initializeLambdaRuntime` is responsible for creation of the new instance of the runtime and initialization of the both lambda environment and application context,
- `start` is just what it says on the tin; it starts the actual thingy,
- `waitUntilInterrupted()` keeps the main loop running and waits for the termination by AWS Lambda environment.

It is possible to pause the runtime by calling `pause()` and shutdown it completely by calling `shutdown()`.

## Lambda template g8

For the convenience of creating a new lambda project there is [a template in g8 format](https://github.com/encalmo/scala-aws-lambda-seed.g8). One has to run:

```sh
sbt new encalmo/scala-aws-lambda-seed.g8 --branch main --lambdaName="ExampleLambda" --package="org.encalmo.lambda.example" --awsAccountId="047719648492" --awsRegion="eu-central-1" -o scala-aws-lambda-seed
```
where:
- `lambdaName` - the name of the lambda function
- `package` - the name of the main lambda package
- `awsAccountId` - required for Github Actions and tests config
- `awsRegion` - required for Github Actions and tests config

## Lambda deployment

Deployment of the lambda function using custom runtime requires the following steps:

- compilation by GraalVM to produce `bootstrap` executable,
- packaging into a ZIP archive,
- [uploading the package into AWS Lambda either manually or using the Lambda API](https://docs.aws.amazon.com/lambda/latest/dg/configuration-function-zip.html)

The example **Github Action** to automate those steps is included in the g8 template, and in the lambda examples. See [buildAndDeployLambda.yaml](https://github.com/encalmo/scala-aws-lambda-example/blob/main/.github/workflows/buildAndDeployLambda.yaml).

Action does the following steps:

- setup scala environment
- setup AWS credentials, requires a role with a github identity provider trust setup
- execute one of two possible scripts depending on the runtime choice, either
   - [buildAndDeployLambdaNativePackage.sh](https://github.com/encalmo/scala-aws-lambda-example/blob/main/scripts/buildAndDeployLambdaNativePackage.sh)
   - or [buildAndDeployLambdaAssembly.sh](https://github.com/encalmo/scala-aws-lambda-example/blob/main/scripts/buildAndDeployLambdaAssembly.sh)
- build deployment package, either `function.zip` or `assembly.jar`
- deploy the package with the help of [deployLambda.sc](https://github.com/encalmo/scala-aws-lambda-example/blob/main/scripts/deployLambda.sc) script.

## Java21 runtime compatibility

Custom runtime implements additionally [`RequestStreamHandler`](https://docs.aws.amazon.com/lambda/latest/dg/java-handler.html) interface from AWS Lambda SDK to make it possible to deploy packaged fatjar using a standard `java21` runtime, without graalvm precompilation.

## Logging

Custom runtime provides built-in support for making your logging experience both simple and modern. All the system output produced during the invocation of your function can be captured and nicely formatted in a CloudWatch friendly JSON format. Each invocation of the function will result in only three log entries:

`REQUEST` entry consists of an input `request` field and the lambda execution metadata where `id` is a simple counter of same-environment invocations.
```json
{
    "log": "REQUEST",
    "lambda": "ExampleLambda",
    "id": 4,
    "request": "\"Scalar 2025\"",
    "lambdaVersion": "$LATEST",
    "lambdaRequestId": "ba2fdf26-c84e-46fb-8ece-7568362ffd83",
    "timestamp": "1743000856266",
    "datetime": "2025-03-26T14:54:16.266789Z[UTC]",
    "maxMemory": 67108864,
    "totalMemory": 67108864,
    "freeMemory": 64487424
}
```
`LOGS` entry contains an array of all system ouput lines produced during single function invocation:
```json
{
    "log": "LOGS",
    "lambda": "ExampleLambda",
    "id": 4,
    "logs": [
        "+000000: Sending greeting: Hello \"Scalar 2025\"!",
        "+000027: How are you doing today?"
    ],
    "lambdaVersion": "$LATEST",
    "lambdaRequestId": "ba2fdf26-c84e-46fb-8ece-7568362ffd83"
}
```
`RESPONSE` entry consists of an output `response` field, optionally repeated `request` field, lambda execution metadata and embeded metrics (e.g. duration).
```json
{
    "log": "RESPONSE",
    "lambda": "ExampleLambda",
    "id": 4,
    "request": "\"Scalar 2025\"",
    "response": {
        "message": "Hello \"Scalar 2025\"!"
    },
    "lambdaVersion": "$LATEST",
    "lambdaRequestId": "ba2fdf26-c84e-46fb-8ece-7568362ffd83",
    "timestamp": "1743000856304",
    "datetime": "2025-03-26T14:54:16.304103Z[UTC]",
    "duration": 38,
    "maxMemory": 67108864,
    "totalMemory": 67108864,
    "freeMemory": 64487424,
    "_aws": {
        "Timestamp": 1743000856304,
        "CloudWatchMetrics": [
            {
                "Namespace": "lambda-ExampleLambda-metrics",
                "Dimensions": [
                    [
                        "lambdaVersion"
                    ]
                ],
                "Metrics": [
                    {
                        "Name": "duration",
                        "Unit": "Milliseconds",
                        "StorageResolution": 60
                    }
                ]
            }
        ]
    }
}
```

Logging support is configured via environment variables:
|key|values|description
|---|---|---
|LAMBDA_RUNTIME_DEBUG_MODE|`ON` or `OFF`|enables logging of request, response and invocation log
|LAMBDA_RUNTIME_TRACE_MODE|`ON` or `OFF`|enables logging of runtime internal events
|ANSI_COLORS_MODE|`ON` or `OFF`|whether to filter out or not ansi color sequences
|LAMBDA_RUNTIME_LOG_TYPE|`STRUCTURED` or `PLAIN`|whether to output log events as JSON or plain text
|LAMBDA_RUNTIME_LOG_FORMAT|`JSON_ARRAY` or `JSON_STRING`| whether to combine log events between request and response as an array of strings or a single string.
|LAMBDA_RUNTIME_LOG_RESPONSE_INCLUDE_REQUEST|`ON` or `OFF`| when `ON` request input will be logged two times, first as a REQUEST event, then again repeated in a RESPONSE event in order to facilitate CloudWatch query filtering on both input and output fields at the same time.

## Testing

Custom runtime supports unit testing out-of the box via dedicated method, reducing the need for an HTTP server-client setup:
```scala
def test(input: String, overrides: Map[String, String] = Map.empty): String
```
where `overrides` is a map of environment variables overrides.

Unit testing your function can be as easy as writing:
```scala
val output = myFunction().test(input = "Hello!")
```

## Running custom runtime locally

In case you want to invoke your function manually in a local environment, it is possible to start your function via a simple AWS Lambda execution environment simulator implemented in [scala-aws-lambda-local-host](https://github.com/encalmo/scala-aws-lambda-local-host).

Run:
```sh
scala run --dependency=org.encalmo::scala-aws-lambda-local-host:0.9.1 \
    --main-class org.encalmo.lambda.host.LocalLambdaHost \
    -- \
    --mode=browser \
    --lambda-script="scala run --main-class org.encalmo.lambda.example.ExampleLambda2 ." \
    --lambda-name=ExampleLambda
```
where:
- `lambda-script` is a command to start your function.
- `mode` can be either `browser` or `terminal`

## Lambda examples

See an example lambda implemented in [TestEchoLambda](https://github.com/encalmo/scala-aws-lambda-runtime/blob/main/TestEchoLambda.scala).

### The simplest lambda example

See: https://github.com/encalmo/scala-aws-lambda-example/blob/main/ExampleLambda0.scala

```scala
import org.encalmo.lambda.{LambdaContext, SimpleLambdaRuntime}
import scala.annotation.static

object ExampleLambda0 {
  /* Custom runtime entry point */
  @static def main(args: Array[String]): Unit = new ExampleLambda0().run()
}

class ExampleLambda0 extends SimpleLambdaRuntime {

 /* Here comes the real job of processing input event and rendering some output. */
  override def handleRequest(input: String)(using LambdaContext, ApplicationContext): String = {
    input
  }

}
```

### Example of lambda with initialized ApplicationContext

See: https://github.com/encalmo/scala-aws-lambda-example/blob/main/ExampleLambda1.scala

```scala
import org.encalmo.lambda.{LambdaContext,LambdaEnvironment,LambdaRuntime}
import scala.annotation.static
import org.encalmo.utils.JsonUtils.*

object ExampleLambda1 {
  
  /* Custom runtime entry point */
  @static def main(args: Array[String]): Unit = new ExampleLambda1().run()

  case class Config(greeting: String) derives upickle.default.ReadWriter
  case class Response(message: String) derives upickle.default.ReadWriter
}

class ExampleLambda1 extends LambdaRuntime {

  import ExampleLambda1.*

  /* Config is our application context initialized once. */
  type ApplicationContext = Config

  /* Here we build our config instance by reading lambda environment variable defining greeting template. */
  override def initialize(using environment: LambdaEnvironment): Config = {
    
    val greeting = environment
      .maybeGetProperty("LAMBDA_GREETING")
      .getOrElse("Hello <input>!")

    environment.info(
      s"Initializing ${environment.getFunctionName()} with a greeting $greeting"
    )

    Config(greeting)
  }

  /* Here comes the real job of processing input event and rendering some output. */
  override inline def handleRequest(input: String)(using lambdaContext: LambdaContext, config: Config): String = {
    val response = Response(config.greeting.replace("<input>", input))
    response.writeAsString
  }

}
```

### Example of a lambda with AwsClient setup and reading of managed secrets

See: https://github.com/encalmo/scala-aws-lambda-example/blob/main/ExampleLambda2.scala

This example is similar to previous one with exception of ApplicationContent defined as a named tuple of two contextual objects: onfig and awsClient.

The greeting template comes not from lambda environment variables directly but from AWS SecretsManager resource(s) defined in an environment variable named `ENVIRONMENT_SECRETS`.

Lambda constructor takes an optional *AwsClient* instance to allow testing using *AwsClient*'s stubs.

*AwsClient* class comes from [scala-aws-client](https://github.com/encalmo/scala-aws-client) and provides connectivity to the AWS services.

Secondary lambda class constructor is required if alternative deployment using `java21` runtime is required. 

```scala
class ExampleLambda2(maybeAwsClient: Option[AwsClient]) extends LambdaRuntime {

  // required for java runtime handler example
  final def this() = this(None)

  import ExampleLambda2.*

  type ApplicationContext = (config: Config, awsClient: AwsClient)

  override def initialize(using environment: LambdaEnvironment): ApplicationContext = {
    val awsClient = maybeAwsClient
      .getOrElse(AwsClient.initializeWithProperties(environment.maybeGetProperty))

    val secrets = LambdaSecrets.retrieveSecrets(environment.maybeGetProperty)

    val greeting = secrets
      .get("SECRET_LAMBDA_GREETING")
      .getOrElse("Hello <input>!")

    environment.info(
      s"Initializing ${environment.getFunctionName()} with a greeting $greeting"
    )

    val config = Config(greeting)

    (config, awsClient)
  }

  override inline def handleRequest(
      input: String
  )(using lambdaConfig: LambdaContext, context: ApplicationContext): String = {
    val greeting = context.config.greeting.replace("<input>", input)
    println(s"Sending greeting: $greeting")
    Response(greeting).writeAsString
  }

}
```

## Project content

```
├── .github
│   └── workflows
│       ├── pages.yaml
│       ├── release.yaml
│       └── test.yaml
│
├── .gitignore
├── .scalafmt.conf
├── AnsiColor.scala
├── EventHandler.scala
├── EventHandlerTag.scala
├── LambdaContext.scala
├── LambdaEnvironment.scala
├── LambdaRuntime.scala
├── LambdaRuntime.test.scala
├── LambdaServiceFixture.test.scala
├── LICENSE
├── Loggers.scala
├── project.scala
├── README.md
├── test.sh
└── TestEchoLambda.scala
```


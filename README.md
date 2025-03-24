<a href="https://github.com/encalmo/scala-aws-lambda-runtime">![GitHub](https://img.shields.io/badge/github-%23121011.svg?style=for-the-badge&logo=github&logoColor=white)</a> <a href="https://central.sonatype.com/artifact/org.encalmo/scala-aws-lambda-runtime_3" target="_blank">![Maven Central Version](https://img.shields.io/maven-central/v/org.encalmo/scala-aws-lambda-runtime_3?style=for-the-badge)</a> <a href="https://encalmo.github.io/scala-aws-lambda-runtime/scaladoc/org/encalmo/lambda.html" target="_blank"><img alt="Scaladoc" src="https://img.shields.io/badge/docs-scaladoc-red?style=for-the-badge"></a>

# scala-aws-lambda-runtime

This Scala3 library provides a custom [AWS Lambda](https://aws.amazon.com/pm/lambda) runtime for building functions using Scala3.

Read more about AWS Lambda: 
- https://docs.aws.amazon.com/lambda/latest/dg/runtimes-custom.html
- https://docs.aws.amazon.com/lambda/latest/dg/runtimes-api.html
- https://docs.aws.amazon.com/lambda/latest/dg/runtimes-walkthrough.html

## Table of content

- [Dependecies](#dependencies)
- [Usage](#usage)
- [Handler API](#handler-api)
   - [initialize](#initialize)

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

Each lambda is compiled into a standalone application binary using GraalVM. The entrty point is a main method defined on the lamda's companion object, like 

```scala
object ExampleLambda {
  @static def main(args: Array[String]): Unit = new ExampleLambda().run()
}
```

The name of the main class must be declared in your build for graalvm to work properly, e.g.:
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

## Examples

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

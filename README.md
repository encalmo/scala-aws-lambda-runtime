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

```
├── .github
│   └── workflows
│       ├── pages.yaml
│       ├── release.yaml
│       └── test.yaml
│
├── .gitignore
├── .scalafmt.conf
├── _site
│   └── scaladoc
│       ├── favicon.ico
│       ├── fonts
│       │   ├── dotty-icons.ttf
│       │   ├── dotty-icons.woff
│       │   ├── FiraCode-Regular.ttf
│       │   ├── Inter-Bold.ttf
│       │   ├── Inter-Medium.ttf
│       │   ├── Inter-Regular.ttf
│       │   └── Inter-SemiBold.ttf
│       │
│       ├── hljs
│       │   ├── highlight.pack.js
│       │   └── LICENSE
│       │
│       ├── images
│       │   ├── banner-icons
│       │   │   ├── error.svg
│       │   │   ├── info.svg
│       │   │   ├── neutral.svg
│       │   │   ├── success.svg
│       │   │   └── warning.svg
│       │   │
│       │   ├── bulb
│       │   │   ├── dark
│       │   │   │   └── default.svg
│       │   │   │
│       │   │   └── light
│       │   │       └── default.svg
│       │   │
│       │   ├── class-big.svg
│       │   ├── class-dark-big.svg
│       │   ├── class-dark.svg
│       │   ├── class.svg
│       │   ├── class_comp.svg
│       │   ├── def-big.svg
│       │   ├── def-dark-big.svg
│       │   ├── discord-icon-black.png
│       │   ├── discord-icon-white.png
│       │   ├── enum-big.svg
│       │   ├── enum-dark-big.svg
│       │   ├── enum-dark.svg
│       │   ├── enum.svg
│       │   ├── enum_comp.svg
│       │   ├── footer-icon
│       │   │   ├── dark
│       │   │   │   └── default.svg
│       │   │   │
│       │   │   └── light
│       │   │       └── default.svg
│       │   │
│       │   ├── github-icon-black.png
│       │   ├── github-icon-white.png
│       │   ├── gitter-icon-black.png
│       │   ├── gitter-icon-white.png
│       │   ├── given-big.svg
│       │   ├── given-dark-big.svg
│       │   ├── given-dark.svg
│       │   ├── given.svg
│       │   ├── icon-buttons
│       │   │   ├── arrow-down
│       │   │   │   ├── dark
│       │   │   │   │   ├── active.svg
│       │   │   │   │   ├── default.svg
│       │   │   │   │   ├── disabled.svg
│       │   │   │   │   ├── focus.svg
│       │   │   │   │   ├── hover.svg
│       │   │   │   │   └── selected.svg
│       │   │   │   │
│       │   │   │   └── light
│       │   │   │       ├── active.svg
│       │   │   │       ├── default.svg
│       │   │   │       ├── disabled.svg
│       │   │   │       ├── focus.svg
│       │   │   │       ├── hover.svg
│       │   │   │       └── selected.svg
│       │   │   │
│       │   │   ├── arrow-right
│       │   │   │   ├── dark
│       │   │   │   │   ├── active.svg
│       │   │   │   │   ├── default.svg
│       │   │   │   │   ├── disabled.svg
│       │   │   │   │   ├── focus.svg
│       │   │   │   │   ├── hover.svg
│       │   │   │   │   └── selected.svg
│       │   │   │   │
│       │   │   │   └── light
│       │   │   │       ├── active.svg
│       │   │   │       ├── default.svg
│       │   │   │       ├── disabled.svg
│       │   │   │       ├── focus.svg
│       │   │   │       ├── hover.svg
│       │   │   │       └── selected.svg
│       │   │   │
│       │   │   ├── close
│       │   │   │   ├── dark
│       │   │   │   │   ├── active.svg
│       │   │   │   │   ├── default.svg
│       │   │   │   │   ├── disabled.svg
│       │   │   │   │   ├── focus.svg
│       │   │   │   │   ├── hover.svg
│       │   │   │   │   └── selected.svg
│       │   │   │   │
│       │   │   │   └── light
│       │   │   │       ├── active.svg
│       │   │   │       ├── default.svg
│       │   │   │       ├── disabled.svg
│       │   │   │       ├── focus.svg
│       │   │   │       ├── hover.svg
│       │   │   │       └── selected.svg
│       │   │   │
│       │   │   ├── copy
│       │   │   │   ├── dark
│       │   │   │   │   ├── active.svg
│       │   │   │   │   ├── default.svg
│       │   │   │   │   ├── disabled.svg
│       │   │   │   │   ├── focus.svg
│       │   │   │   │   ├── hover.svg
│       │   │   │   │   └── selected.svg
│       │   │   │   │
│       │   │   │   └── light
│       │   │   │       ├── active.svg
│       │   │   │       ├── default.svg
│       │   │   │       ├── disabled.svg
│       │   │   │       ├── focus.svg
│       │   │   │       ├── hover.svg
│       │   │   │       └── selected.svg
│       │   │   │
│       │   │   ├── discord
│       │   │   │   ├── dark
│       │   │   │   │   ├── active.svg
│       │   │   │   │   ├── default.svg
│       │   │   │   │   ├── disabled.svg
│       │   │   │   │   ├── focus.svg
│       │   │   │   │   ├── hover.svg
│       │   │   │   │   └── selected.svg
│       │   │   │   │
│       │   │   │   └── light
│       │   │   │       ├── active.svg
│       │   │   │       ├── default.svg
│       │   │   │       ├── disabled.svg
│       │   │   │       ├── focus.svg
│       │   │   │       ├── hover.svg
│       │   │   │       └── selected.svg
│       │   │   │
│       │   │   ├── gh
│       │   │   │   ├── dark
│       │   │   │   │   ├── active.svg
│       │   │   │   │   ├── default.svg
│       │   │   │   │   ├── disabled.svg
│       │   │   │   │   ├── focus.svg
│       │   │   │   │   ├── hover.svg
│       │   │   │   │   └── selected.svg
│       │   │   │   │
│       │   │   │   └── light
│       │   │   │       ├── active.svg
│       │   │   │       ├── default.svg
│       │   │   │       ├── disabled.svg
│       │   │   │       ├── focus.svg
│       │   │   │       ├── hover.svg
│       │   │   │       └── selected.svg
│       │   │   │
│       │   │   ├── gitter
│       │   │   │   ├── dark
│       │   │   │   │   ├── active.svg
│       │   │   │   │   ├── default.svg
│       │   │   │   │   ├── disabled.svg
│       │   │   │   │   ├── focus.svg
│       │   │   │   │   ├── hover.svg
│       │   │   │   │   └── selected.svg
│       │   │   │   │
│       │   │   │   └── light
│       │   │   │       ├── active.svg
│       │   │   │       ├── default.svg
│       │   │   │       ├── disabled.svg
│       │   │   │       ├── focus.svg
│       │   │   │       ├── hover.svg
│       │   │   │       └── selected.svg
│       │   │   │
│       │   │   ├── hamburger
│       │   │   │   ├── dark
│       │   │   │   │   ├── active.svg
│       │   │   │   │   ├── default.svg
│       │   │   │   │   ├── disabled.svg
│       │   │   │   │   ├── focus.svg
│       │   │   │   │   ├── hover.svg
│       │   │   │   │   └── selected.svg
│       │   │   │   │
│       │   │   │   └── light
│       │   │   │       ├── active.svg
│       │   │   │       ├── default.svg
│       │   │   │       ├── disabled.svg
│       │   │   │       ├── focus.svg
│       │   │   │       ├── hover.svg
│       │   │   │       └── selected.svg
│       │   │   │
│       │   │   ├── link
│       │   │   │   ├── dark
│       │   │   │   │   ├── active.svg
│       │   │   │   │   ├── default.svg
│       │   │   │   │   ├── disabled.svg
│       │   │   │   │   ├── focus.svg
│       │   │   │   │   ├── hover.svg
│       │   │   │   │   └── selected.svg
│       │   │   │   │
│       │   │   │   └── light
│       │   │   │       ├── active.svg
│       │   │   │       ├── default.svg
│       │   │   │       ├── disabled.svg
│       │   │   │       ├── focus.svg
│       │   │   │       ├── hover.svg
│       │   │   │       └── selected.svg
│       │   │   │
│       │   │   ├── menu-animated
│       │   │   │   ├── dark
│       │   │   │   │   ├── active.svg
│       │   │   │   │   ├── default.svg
│       │   │   │   │   ├── disabled.svg
│       │   │   │   │   ├── focus.svg
│       │   │   │   │   ├── hover.svg
│       │   │   │   │   └── selected.svg
│       │   │   │   │
│       │   │   │   └── light
│       │   │   │       ├── active.svg
│       │   │   │       ├── default.svg
│       │   │   │       ├── disabled.svg
│       │   │   │       ├── focus.svg
│       │   │   │       ├── hover.svg
│       │   │   │       └── selected.svg
│       │   │   │
│       │   │   ├── menu-animated-open
│       │   │   │   ├── dark
│       │   │   │   │   ├── active.svg
│       │   │   │   │   ├── default.svg
│       │   │   │   │   ├── disabled.svg
│       │   │   │   │   ├── focus.svg
│       │   │   │   │   ├── hover.svg
│       │   │   │   │   └── selected.svg
│       │   │   │   │
│       │   │   │   └── light
│       │   │   │       ├── active.svg
│       │   │   │       ├── default.svg
│       │   │   │       ├── disabled.svg
│       │   │   │       ├── focus.svg
│       │   │   │       ├── hover.svg
│       │   │   │       └── selected.svg
│       │   │   │
│       │   │   ├── minus
│       │   │   │   ├── dark
│       │   │   │   │   ├── active.svg
│       │   │   │   │   ├── default.svg
│       │   │   │   │   ├── disabled.svg
│       │   │   │   │   ├── focus.svg
│       │   │   │   │   ├── hover.svg
│       │   │   │   │   └── selected.svg
│       │   │   │   │
│       │   │   │   └── light
│       │   │   │       ├── active.svg
│       │   │   │       ├── default.svg
│       │   │   │       ├── disabled.svg
│       │   │   │       ├── focus.svg
│       │   │   │       ├── hover.svg
│       │   │   │       └── selected.svg
│       │   │   │
│       │   │   ├── moon
│       │   │   │   ├── dark
│       │   │   │   │   ├── active.svg
│       │   │   │   │   ├── default.svg
│       │   │   │   │   ├── disabled.svg
│       │   │   │   │   ├── focus.svg
│       │   │   │   │   ├── hover.svg
│       │   │   │   │   └── selected.svg
│       │   │   │   │
│       │   │   │   └── light
│       │   │   │       ├── active.svg
│       │   │   │       ├── default.svg
│       │   │   │       ├── disabled.svg
│       │   │   │       ├── focus.svg
│       │   │   │       ├── hover.svg
│       │   │   │       └── selected.svg
│       │   │   │
│       │   │   ├── plus
│       │   │   │   ├── dark
│       │   │   │   │   ├── active.svg
│       │   │   │   │   ├── default.svg
│       │   │   │   │   ├── disabled.svg
│       │   │   │   │   ├── focus.svg
│       │   │   │   │   ├── hover.svg
│       │   │   │   │   └── selected.svg
│       │   │   │   │
│       │   │   │   └── light
│       │   │   │       ├── active.svg
│       │   │   │       ├── default.svg
│       │   │   │       ├── disabled.svg
│       │   │   │       ├── focus.svg
│       │   │   │       ├── hover.svg
│       │   │   │       └── selected.svg
│       │   │   │
│       │   │   ├── search
│       │   │   │   ├── dark
│       │   │   │   │   ├── active.svg
│       │   │   │   │   ├── default.svg
│       │   │   │   │   ├── disabled.svg
│       │   │   │   │   ├── focus.svg
│       │   │   │   │   ├── hover.svg
│       │   │   │   │   └── selected.svg
│       │   │   │   │
│       │   │   │   └── light
│       │   │   │       ├── active.svg
│       │   │   │       ├── default.svg
│       │   │   │       ├── disabled.svg
│       │   │   │       ├── focus.svg
│       │   │   │       ├── hover.svg
│       │   │   │       └── selected.svg
│       │   │   │
│       │   │   ├── sun
│       │   │   │   ├── dark
│       │   │   │   │   ├── active.svg
│       │   │   │   │   ├── default.svg
│       │   │   │   │   ├── disabled.svg
│       │   │   │   │   ├── focus.svg
│       │   │   │   │   ├── hover.svg
│       │   │   │   │   └── selected.svg
│       │   │   │   │
│       │   │   │   └── light
│       │   │   │       ├── active.svg
│       │   │   │       ├── default.svg
│       │   │   │       ├── disabled.svg
│       │   │   │       ├── focus.svg
│       │   │   │       ├── hover.svg
│       │   │   │       └── selected.svg
│       │   │   │
│       │   │   └── twitter
│       │   │       ├── dark
│       │   │       │   ├── active.svg
│       │   │       │   ├── default.svg
│       │   │       │   ├── disabled.svg
│       │   │       │   ├── focus.svg
│       │   │       │   ├── hover.svg
│       │   │       │   └── selected.svg
│       │   │       │
│       │   │       └── light
│       │   │           ├── active.svg
│       │   │           ├── default.svg
│       │   │           ├── disabled.svg
│       │   │           ├── focus.svg
│       │   │           ├── hover.svg
│       │   │           └── selected.svg
│       │   │
│       │   ├── info
│       │   │   ├── dark
│       │   │   │   └── default.svg
│       │   │   │
│       │   │   └── light
│       │   │       └── default.svg
│       │   │
│       │   ├── inkuire.svg
│       │   ├── method-big.svg
│       │   ├── method-dark-big.svg
│       │   ├── method-dark.svg
│       │   ├── method.svg
│       │   ├── no-results-icon.svg
│       │   ├── object-big.svg
│       │   ├── object-dark-big.svg
│       │   ├── object-dark.svg
│       │   ├── object.svg
│       │   ├── object_comp.svg
│       │   ├── package-big.svg
│       │   ├── package-dark-big.svg
│       │   ├── package-dark.svg
│       │   ├── package.svg
│       │   ├── scaladoc_logo.svg
│       │   ├── scaladoc_logo_dark.svg
│       │   ├── static-big.svg
│       │   ├── static-dark-big.svg
│       │   ├── static-dark.svg
│       │   ├── static.svg
│       │   ├── thick-dark.svg
│       │   ├── thick.svg
│       │   ├── trait-big.svg
│       │   ├── trait-dark-big.svg
│       │   ├── trait-dark.svg
│       │   ├── trait.svg
│       │   ├── trait_comp.svg
│       │   ├── twitter-icon-black.png
│       │   ├── twitter-icon-white.png
│       │   ├── type-big.svg
│       │   ├── type-dark-big.svg
│       │   ├── type-dark.svg
│       │   ├── type.svg
│       │   ├── val-big.svg
│       │   ├── val-dark-big.svg
│       │   ├── val-dark.svg
│       │   └── val.svg
│       │
│       ├── index.html
│       ├── inkuire-db.json
│       ├── META-INF
│       │   └── MANIFEST.MF
│       │
│       ├── org
│       │   └── encalmo
│       │       ├── lambda
│       │       │   ├── AnsiColor$.html
│       │       │   ├── EventHandler.html
│       │       │   ├── EventHandlerTag.html
│       │       │   ├── LambdaContext$$Logger$.html
│       │       │   ├── LambdaContext$.html
│       │       │   ├── LambdaContext.html
│       │       │   ├── LambdaEnvironment$$Logger$.html
│       │       │   ├── LambdaEnvironment$.html
│       │       │   ├── LambdaEnvironment.html
│       │       │   ├── LambdaRuntime$.html
│       │       │   ├── LambdaRuntime$Instance.html
│       │       │   ├── LambdaRuntime.html
│       │       │   ├── NoAnsiColorJsonArray.html
│       │       │   ├── NoAnsiColorJsonString.html
│       │       │   ├── NoAnsiColors$.html
│       │       │   ├── NoAnsiColorsSingleLine.html
│       │       │   ├── NoOpPrinter$.html
│       │       │   ├── SimpleLambdaRuntime.html
│       │       │   ├── SystemOutLambdaLogger$.html
│       │       │   ├── TestEchoLambda$.html
│       │       │   └── TestEchoLambda.html
│       │       │
│       │       └── lambda.html
│       │
│       ├── scaladoc.version
│       ├── scripts
│       │   ├── common
│       │   │   ├── component.js
│       │   │   └── utils.js
│       │   │
│       │   ├── components
│       │   │   ├── DocumentableList.js
│       │   │   ├── Filter.js
│       │   │   ├── FilterBar.js
│       │   │   ├── FilterGroup.js
│       │   │   └── Input.js
│       │   │
│       │   ├── contributors.js
│       │   ├── data.js
│       │   ├── hljs-scala3.js
│       │   ├── inkuire-config.json
│       │   ├── inkuire-worker.js
│       │   ├── inkuire.js
│       │   ├── scaladoc-scalajs.js
│       │   ├── scastieConfiguration.js
│       │   ├── searchData.js
│       │   ├── theme.js
│       │   └── ux.js
│       │
│       ├── styles
│       │   ├── apistyles.css
│       │   ├── code-snippets.css
│       │   ├── content-contributors.css
│       │   ├── dotty-icons.css
│       │   ├── filter-bar.css
│       │   ├── fontawesome.css
│       │   ├── nord-light.css
│       │   ├── searchbar.css
│       │   ├── social-links.css
│       │   ├── staticsitestyles.css
│       │   ├── theme
│       │   │   ├── bundle.css
│       │   │   ├── components
│       │   │   │   ├── bundle.css
│       │   │   │   └── button
│       │   │   │       └── bundle.css
│       │   │   │
│       │   │   └── layout
│       │   │       └── bundle.css
│       │   │
│       │   └── versions-dropdown.css
│       │
│       └── webfonts
│           ├── fa-brands-400.eot
│           ├── fa-brands-400.svg
│           ├── fa-brands-400.ttf
│           ├── fa-brands-400.woff
│           ├── fa-brands-400.woff2
│           ├── fa-regular-400.eot
│           ├── fa-regular-400.svg
│           ├── fa-regular-400.ttf
│           ├── fa-regular-400.woff
│           ├── fa-regular-400.woff2
│           ├── fa-solid-900.eot
│           ├── fa-solid-900.svg
│           ├── fa-solid-900.ttf
│           ├── fa-solid-900.woff
│           └── fa-solid-900.woff2
│
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
├── scala-aws-lambda-runtime_3-0.9.11.zip
├── test.sh
└── TestEchoLambda.scala
```


<a href="https://github.com/encalmo/scala-aws-lambda-runtime">![GitHub](https://img.shields.io/badge/github-%23121011.svg?style=for-the-badge&logo=github&logoColor=white)</a> <a href="https://central.sonatype.com/artifact/org.encalmo/scala-aws-lambda-runtime_3" target="_blank">![Maven Central Version](https://img.shields.io/maven-central/v/org.encalmo/scala-aws-lambda-runtime_3?style=for-the-badge)</a> <a href="https://encalmo.github.io/scala-aws-lambda-runtime/scaladoc/org/encalmo/lambda.html" target="_blank"><img alt="Scaladoc" src="https://img.shields.io/badge/docs-scaladoc-red?style=for-the-badge"></a>

# scala-aws-lambda-runtime

This Scala3 library provides a custom [AWS Lambda](https://aws.amazon.com/pm/lambda) runtime for building functions using Scala3.

See: 
- https://docs.aws.amazon.com/lambda/latest/dg/runtimes-custom.html
- https://docs.aws.amazon.com/lambda/latest/dg/runtimes-api.html
- https://docs.aws.amazon.com/lambda/latest/dg/runtimes-walkthrough.html

## Dependencies

   - [Scala](https://www.scala-lang.org/) >= 3.3.5
   - com.amazonaws [**aws-lambda-java-core** 1.2.3](https://central.sonatype.com/artifact/com.amazonaws/aws-lambda-java-core)

## Usage

Use with SBT

    libraryDependencies += "org.encalmo" %% "scala-aws-lambda-runtime" % "0.9.8"

or with SCALA-CLI

    //> using dep org.encalmo::scala-aws-lambda-runtime:0.9.8

## API

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

## Examples

See an example lambda implemented in [TestEchoLambda](https://github.com/encalmo/scala-aws-lambda-runtime/blob/main/TestEchoLambda.scala) and in [scala-aws-lambda-example](https://github.com/encalmo/scala-aws-lambda-example)

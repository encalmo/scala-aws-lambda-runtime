# scala-aws-lambda-runtime

This Scala3 library provides a custom [AWS Lambda](https://aws.amazon.com/pm/lambda) runtime for building functions using Scala3.

See: 
- https://docs.aws.amazon.com/lambda/latest/dg/runtimes-custom.html
- https://docs.aws.amazon.com/lambda/latest/dg/runtimes-walkthrough.html

## Dependencies

- JVM 21
- Scala 3.3.5

## Usage

Use with SBT

    libraryDependencies += "org.encalmo" %% "scala-aws-lambda-runtime" % "0.9.0"

or with SCALA-CLI

    //> using dep org.encalmo::scala-aws-lambda-runtime:0.9.0

## Examples

See an example lambda implemented in [TestEchoLambda](TestEchoLambda.scala)

<a href="https://github.com/encalmo/scala-aws-lambda-runtime">![GitHub](https://img.shields.io/badge/github-%23121011.svg?style=for-the-badge&logo=github&logoColor=white)</a> <a href="https://central.sonatype.com/artifact/org.encalmo/opaque-type_3" target="_blank">![Maven Central Version](https://img.shields.io/maven-central/v/org.encalmo/opaque-type_3?style=for-the-badge)</a> <a href="https://encalmo.github.io/scala-aws-lambda-runtime/scaladoc/org/encalmo/lambda.html" target="_blank"><img alt="Scaladoc" src="https://img.shields.io/badge/docs-scaladoc-red?style=for-the-badge"></a>

# scala-aws-lambda-runtime

This Scala3 library provides a custom [AWS Lambda](https://aws.amazon.com/pm/lambda) runtime for building functions using Scala3.

See: 
- https://docs.aws.amazon.com/lambda/latest/dg/runtimes-custom.html
- https://docs.aws.amazon.com/lambda/latest/dg/runtimes-api.html
- https://docs.aws.amazon.com/lambda/latest/dg/runtimes-walkthrough.html

## Dependencies

- Scala >= 3.3.5

## Usage

Use with SBT

    libraryDependencies += "org.encalmo" %% "scala-aws-lambda-runtime" % "0.9.7"

or with SCALA-CLI

    //> using dep org.encalmo::scala-aws-lambda-runtime:0.9.7

## Examples

See an example lambda implemented in [TestEchoLambda](https://github.com/encalmo/scala-aws-lambda-runtime/blob/main/TestEchoLambda.scala) and in [scala-aws-lambda-example](https://github.com/encalmo/scala-aws-lambda-example)

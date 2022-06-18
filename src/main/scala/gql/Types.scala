package gql

import cats.implicits._
import cats.data._
import io.circe._
import Value._
import cats._

object Types {
  sealed trait Input[A] {
    def decode(value: Value): Either[String, A]
  }

  object Input {
    final case class Arr[A](of: Input[A]) extends Input[Vector[A]] {
      def decode(value: Value): Either[String, Vector[A]] =
        value match {
          case JsonValue(ja) if ja.isArray =>
            ja.asArray.get.traverse(j => of.decode(Value.JsonValue(j)))
          case ArrayValue(v) => v.traverse(of.decode)
          case _             => Left(s"expected array type, get ${value.name}")
        }
    }

    final case class Opt[A](of: Input[A]) extends Input[Option[A]] {
      def decode(value: Value): Either[String, Option[A]] =
        if (value.asJson.isNull) Right(None)
        else of.decode(value).map(Some(_))
    }

    // optimization, use a stack instead of a map since we know the order of decoders
    final case class Object[A](
        name: String,
        fields: NonEmptyList[Object.Field[_]],
        decoder: Map[String, _] => A
    ) extends Input[A] {
      def addField[B](newField: Object.Field[B]): Object[(A, B)] =
        Object(name, newField :: fields, m => (decoder(m), m(newField.name).asInstanceOf[B]))

      def decode(value: Value): Either[String, A] = {
        value match {
          case JsonValue(jo) if jo.isObject =>
            val m = jo.asObject.get.toMap

            fields
              .traverse { field =>
                val res =
                  m
                    .get(field.name)
                    .map(x => field.tpe.decode(JsonValue(x))) match {
                    case Some(outcome) => outcome
                    case None          => field.default.toRight(s"missing field ${field.name} in input object $name")
                  }

                res.map(field.name -> _)
              }
              .map(_.toList.toMap)
              .map(decoder)

          case ObjectValue(xs) =>
            fields
              .traverse { field =>
                val res =
                  xs
                    .get(field.name)
                    .map(field.tpe.decode) match {
                    case Some(outcome) => outcome
                    case None          => field.default.toRight(s"missing field ${field.name} in input object $name")
                  }

                res.map(field.name -> _)
              }
              .map(_.toList.toMap)
              .map(decoder)
          case _ => Left(s"expected object for $name, got ${value.name}")
        }
      }
    }
    object Object {
      final case class Fields[A](
          fields: NonEmptyVector[Object.Field[_]],
          decoder: List[_] => A
      )
      implicit lazy val applyForFields = new Apply[Fields] {
        override def map[A, B](fa: Fields[A])(f: A => B): Fields[B] =
          Fields(fa.fields, fa.decoder andThen f)

        override def ap[A, B](ff: Fields[A => B])(fa: Fields[A]): Fields[B] = ???
      }

      final case class Field[A](
          name: String,
          tpe: Input[A],
          default: Option[A] = None
      )
    }
  }

  sealed trait Output[F[_], A] {
    def mapK[G[_]](fk: F ~> G): Output[G, A]
  }

  object Output {
    final case class Arr[F[_], A](of: Output[F, A]) extends Output[F, Vector[A]] {
      def mapK[G[_]](fk: F ~> G): Output[G, Vector[A]] = Arr(of.mapK(fk))
    }

    final case class Opt[F[_], A](of: Output[F, A]) extends Output[F, Option[A]] {
      def mapK[G[_]](fk: F ~> G): Output[G, Option[A]] = Opt(of.mapK(fk))
    }

    final case class Object[F[_], A](
        name: String,
        fields: NonEmptyList[(String, Object.Field[F, A, _])]
    ) extends Output[F, A] {
      def mapK[G[_]](fk: F ~> G): Object[G, A] =
        Object(name, fields.map { case (k, v) => k -> v.mapK(fk) })
    }
    object Object {
      sealed trait Resolution[F[_], +A] {
        def mapK[G[_]](fk: F ~> G): Resolution[G, A]
      }
      final case class PureResolution[F[_], +A](value: A) extends Resolution[F, A] {
        override def mapK[G[_]](fk: F ~> G): Resolution[G, A] =
          PureResolution(value)
      }
      final case class DeferredResolution[F[_], A](f: F[A]) extends Resolution[F, A] {
        override def mapK[G[_]](fk: F ~> G): Resolution[G, A] =
          DeferredResolution(fk(f))
      }

      sealed trait Field[F[_], I, T] {
        def output: Eval[Output[F, T]]

        def mapK[G[_]](fk: F ~> G): Field[G, I, T]
      }

      final case class SimpleField[F[_], I, T](
          resolve: I => Resolution[F, T],
          output: Eval[Output[F, T]]
      ) extends Field[F, I, T] {
        def mapK[G[_]](fk: F ~> G): Field[G, I, T] =
          SimpleField(resolve.andThen(_.mapK(fk)), output.map(_.mapK(fk)))
      }

      final case class Arg[A](
          name: String,
          input: Input[A],
          default: Option[A] = None
      )

      final case class Args[A](
          entries: NonEmptyVector[Arg[_]],
          decode: List[_] => (List[_], A)
      )

      // pure/point does not make sense for args
      implicit lazy val applyForArgs = new Apply[Args] {
        override def map[A, B](fa: Args[A])(f: A => B): Args[B] =
          fa.copy(decode = fa.decode andThen { case (s, a) => (s, f(a)) })

        override def ap[A, B](ff: Args[A => B])(fa: Args[A]): Args[B] =
          Args(
            ff.entries ++: fa.entries,
            { s1 =>
              val (s2, f) = ff.decode(s1)
              val (s3, a) = fa.decode(s2)
              (s3, f(a))
            }
          )
      }

      final case class ArgField[F[_], I, T, A](
          args: Args[A],
          resolve: (I, A) => Resolution[F, T],
          output: Eval[Output[F, T]]
      ) extends Field[F, I, T] {
        def mapK[G[_]](fk: F ~> G): Field[G, I, T] =
          ArgField[G, I, T, A](
            args,
            (i, a) => resolve(i, a).mapK(fk),
            output.map(_.mapK(fk))
          )
      }
    }

    final case class Union[F[_], A](
        name: String,
        types: NonEmptyList[Object[F, A]]
    ) extends Output[F, A] {
      def mapK[G[_]](fk: F ~> G): Union[G, A] =
        Union(
          name,
          types.map(_.mapK(fk))
        )
    }

    final case class Scalar[F[_], A](name: String, encoder: Encoder[A]) extends Output[F, A] {
      def mapK[G[_]](fk: F ~> G): Scalar[G, A] = Scalar(name, encoder)
    }
  }

  // final case class Scalar[A](name: String, decoder: Decoder[A], encoder: Encoder[A]) extends Input[A] with Output[Id, A] {
  //   def decode(value: Value): Either[String, A] = decoder.decodeJson(value.asJson).leftMap(_.show)

  //   def mapK[G[_]](fk: Id ~> G): Output[G, A] = ???
  // }

  final case class Enum[A](name: String, fields: NonEmptyMap[String, A]) extends Input[A] with Output[Id, A] {
    def mapK[G[_]](fk: Id ~> G): Output[G, A] = ???

    def decodeString(s: String): Either[String, A] =
      fields.lookup(s) match {
        case Some(a) => Right(a)
        case None    => Left(s"unknown value $s for enum $name")
      }

    def decode(value: Value): Either[String, A] =
      value match {
        case JsonValue(v) if v.isString => decodeString(v.asString.get)
        case EnumValue(s)               => decodeString(s)
        case _                          => Left(s"expected enum $name, got ${value.name}")
      }
  }
}
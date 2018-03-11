////////////////////////////////////////////////////////////////////////////////
// Argon Design Ltd. Project P8009 Alogic
// Copyright (c) 2018 Argon Design Ltd. All rights reserved.
//
// This file is covered by the BSD (with attribution) license.
// See the LICENSE file for the precise wording of the license.
//
// Module: Alogic Compiler
// Author: Geza Lore
//
// DESCRIPTION:
//
// Parser tests
////////////////////////////////////////////////////////////////////////////////

package com.argondesign.alogic.frontend

import com.argondesign.alogic.AlogicTest
import com.argondesign.alogic.SourceTextConverters._
import com.argondesign.alogic.ast.Trees._
import com.argondesign.alogic.ast.Trees.Expr.ImplicitConversions._
import com.argondesign.alogic.core.CompilerContext
import com.argondesign.alogic.core.Error
import com.argondesign.alogic.core.FlowControlTypes.FlowControlTypeNone
import com.argondesign.alogic.core.FlowControlTypes.FlowControlTypeReady
import com.argondesign.alogic.core.FlowControlTypes.FlowControlTypeValid
import com.argondesign.alogic.core.Message
import com.argondesign.alogic.core.StorageTypes.StorageSliceBubble
import com.argondesign.alogic.core.StorageTypes.StorageSliceBwd
import com.argondesign.alogic.core.StorageTypes.StorageSliceFwd
import com.argondesign.alogic.core.StorageTypes.StorageTypeReg
import com.argondesign.alogic.core.StorageTypes.StorageTypeSlices
import com.argondesign.alogic.core.StorageTypes.StorageTypeWire
import com.argondesign.alogic.core.Types._

import org.scalatest.FreeSpec
import org.scalatest.matchers.MatchResult
import org.scalatest.matchers.Matcher

final class ParserSpec extends FreeSpec with AlogicTest {

  implicit val cc = new CompilerContext

  def beSyntaxError: Matcher[Message] = beSyntaxError("")

  def beSyntaxError(text: String): Matcher[Message] = Matcher { (msg: Message) =>
    val matchesMessage = if (text.isEmpty) {
      msg.msg(0) startsWith "Syntax error: "
    } else {
      msg.msg(0) == s"Syntax error: ${text}"
    }
    MatchResult(
      msg.isInstanceOf[Error] && matchesMessage,
      s"'${msg}' was not a Syntax error message matching 'Syntax error: ${text}'",
      s"'${msg}' was the correct Syntex error message"
    )
  }

  "The parser" - {

    /////////////////////////////////////////////////////////////////////////////
    // Valid input
    /////////////////////////////////////////////////////////////////////////////

    "should accept valid input" - {

      "file without type definitions" in {
        """|fsm foo {
           |
           |}""".asTree[Root] should matchPattern {
          case Root(Nil, _: Entity) =>
        }
        cc.messages shouldBe empty
      }

    }

    /////////////////////////////////////////////////////////////////////////////
    // Invalid input
    /////////////////////////////////////////////////////////////////////////////

    "should not accept input with invalid syntax" - {

      "syntax error" in {
        a[AsTreeSyntaxErrorException] shouldBe thrownBy {
          "$!%".asTree[Root]
        }
        cc.messages.loneElement should beSyntaxError
      }

      "file without entity defintition" in {
        a[AsTreeSyntaxErrorException] should be thrownBy {
          "typedef i8 a;".asTree[Root]
        }
        cc.messages.loneElement should beSyntaxError
      }

      "empty let statement" in {
        a[AsTreeSyntaxErrorException] should be thrownBy {
          "let () for(a=1;a;a--) { a+=b; }".asTree[Stmt]
        }
        cc.messages should not be empty
        cc.messages(0) should beSyntaxError("empty 'let ()' statement")
      }

      "empty do loop" in {
        a[AsTreeSyntaxErrorException] should be thrownBy {
          "do { a=1; } while();".asTree[Stmt]
        }
        cc.messages.loneElement should beSyntaxError("empty 'while ()' condition")
      }

      "empty while loop" in {
        a[AsTreeSyntaxErrorException] should be thrownBy {
          "while ()".asTree[Stmt]
        }
        cc.messages should not be empty
        cc.messages(0) should beSyntaxError("empty 'while ()' condition")
      }

      "missing parameter list after instantiation" in {
        a[AsTreeSyntaxErrorException] should be thrownBy {
          "network a { b = new c; }".asTree[Entity]
        }
        cc.messages should not be empty
        cc.messages(0) should beSyntaxError(
          "missing parameter list '()' after entity name in instantiation 'new c'")
      }

      // TODO: Mandatory blocks
    }

    "should signal error for malformed input" - {}

    /////////////////////////////////////////////////////////////////////////////
    // AST representations
    /////////////////////////////////////////////////////////////////////////////

    "should build correct ASTs for" - {

      "type definitions" - {

        "typedef" in {
          "typedef u8 foo;".asTree[TypeDefinition] shouldBe {
            TypeDefinitionTypedef(Ident("foo"), TypeUInt(Expr(8)))
          }
        }

        "struct" in {
          "struct bar { u8 foo; i2 baz; }".asTree[TypeDefinition] shouldBe {
            TypeDefinitionStruct(
              Ident("bar"),
              List("foo", "baz"),
              List(TypeUInt(Expr(8)), TypeSInt(Expr(2)))
            )
          }
        }

      }

      "declarations" - {
        "scalar" - {
          "without initializer" in {
            "bool a".asTree[Decl] shouldBe Decl(Ident("a"), TypeUInt(Expr(1)), None)
          }

          "with initializer" in {
            "bool b = true".asTree[Decl] shouldBe {
              Decl(Ident("b"), TypeUInt(Expr(1)), Some(ExprInt(false, 1, 1)))
            }
          }
        }

        "array" - {
          "1D" in {
            "i8 c[2]".asTree[Decl] shouldBe {
              Decl(Ident("c"), TypeArray(TypeSInt(Expr(8)), Expr(2)), None)
            }
          }

          "2D" in {
            "i8 d[2][3]".asTree[Decl] shouldBe {
              Decl(Ident("d"), TypeArray(TypeArray(TypeSInt(Expr(8)), Expr(3)), Expr(2)), None)
            }
          }

          "2D array of 2D vector" in {
            "int(8,2) e[5][4]".asTree[Decl] shouldBe {
              Decl(
                Ident("e"),
                TypeArray(TypeArray(TypeVector(TypeSInt(Expr(2)), Expr(8)), Expr(4)), Expr(5)),
                None
              )
            }
          }
        }

        "output" - {
          "no flow control" - {
            "default" in {
              "out i2 a".asTree[Decl] shouldBe {
                Decl(Ident("a"),
                     TypeOut(TypeSInt(Expr(2)), FlowControlTypeNone, StorageTypeReg),
                     None)
              }
            }

            "wire" in {
              "out wire u2 a".asTree[Decl] shouldBe {
                Decl(Ident("a"),
                     TypeOut(TypeUInt(Expr(2)), FlowControlTypeNone, StorageTypeWire),
                     None)
              }
            }
          }

          "valid flow control" - {
            "default" in {
              "out sync i2 a".asTree[Decl] shouldBe {
                Decl(Ident("a"),
                     TypeOut(TypeSInt(Expr(2)), FlowControlTypeValid, StorageTypeReg),
                     None)
              }
            }

            "wire" in {
              "out sync wire i2 a".asTree[Decl] shouldBe {
                Decl(Ident("a"),
                     TypeOut(TypeSInt(Expr(2)), FlowControlTypeValid, StorageTypeWire),
                     None)
              }
            }
          }

          "valid/ready flow control" - {
            "default" in {
              "out sync ready i2 a".asTree[Decl] shouldBe {
                Decl(
                  Ident("a"),
                  TypeOut(TypeSInt(Expr(2)),
                          FlowControlTypeReady,
                          StorageTypeSlices(List(StorageSliceFwd))),
                  None
                )
              }
            }

            "fslice" in {
              "out sync ready fslice i2 a".asTree[Decl] shouldBe {
                Decl(
                  Ident("a"),
                  TypeOut(TypeSInt(Expr(2)),
                          FlowControlTypeReady,
                          StorageTypeSlices(List(StorageSliceFwd))),
                  None
                )
              }
            }

            "bslice" in {
              "out sync ready bslice i2 a".asTree[Decl] shouldBe {
                Decl(
                  Ident("a"),
                  TypeOut(TypeSInt(Expr(2)),
                          FlowControlTypeReady,
                          StorageTypeSlices(List(StorageSliceBwd))),
                  None
                )
              }
            }

            "bubble" in {
              "out sync ready bubble i2 a".asTree[Decl] shouldBe {
                Decl(
                  Ident("a"),
                  TypeOut(TypeSInt(Expr(2)),
                          FlowControlTypeReady,
                          StorageTypeSlices(List(StorageSliceBubble))),
                  None
                )
              }
            }

            "bslice bubble fslice" in {
              "out sync ready bslice bubble fslice i2 a".asTree[Decl] shouldBe {
                Decl(
                  Ident("a"),
                  TypeOut(
                    TypeSInt(Expr(2)),
                    FlowControlTypeReady,
                    StorageTypeSlices(List(StorageSliceBwd, StorageSliceBubble, StorageSliceFwd))
                  ),
                  None
                )
              }
            }
          }
        }

        "inputs" - {
          "no flow control" in {
            "in i2 a".asTree[Decl] shouldBe {
              Decl(Ident("a"), TypeIn(TypeSInt(Expr(2)), FlowControlTypeNone), None)
            }
          }

          "valid flow control" in {
            "in sync i2 a".asTree[Decl] shouldBe {
              Decl(Ident("a"), TypeIn(TypeSInt(Expr(2)), FlowControlTypeValid), None)
            }
          }

          "valid/ready flow control" in {
            "in sync ready i2 a".asTree[Decl] shouldBe {
              Decl(Ident("a"), TypeIn(TypeSInt(Expr(2)), FlowControlTypeReady), None)
            }
          }
        }

        "parameter" in {
          "param i2 a = 2".asTree[Decl] shouldBe {
            Decl(Ident("a"), TypeParam(TypeSInt(Expr(2))), Some(Expr(2)))
          }
        }

        "constant" in {
          "const i2 a = 2".asTree[Decl] shouldBe {
            Decl(Ident("a"), TypeConst(TypeSInt(Expr(2))), Some(Expr(2)))
          }
        }

        "pipeline variable" in {
          "pipeline u8 a".asTree[Decl] shouldBe {
            Decl(Ident("a"), TypePipeline(TypeUInt(Expr(8))), None)
          }
        }
      }

      "types" - {
        "bool" in {
          "bool".asTree[Expr] shouldBe ExprType(TypeUInt(Expr(1)))
        }

        "bool is same as u1" in {
          "bool".asTree[Expr] shouldBe "u1".asTree[Expr]
        }

        "fixed unsigned ints" in {
          forAll(List("u1", "u2", "u3", "u44", "u128")) { str =>
            str.asTree[Expr] shouldBe ExprType(TypeUInt(Expr(str.tail.toInt)))
          }
        }

        "fixed signed ints" in {
          forAll(List("i1", "i2", "i3", "i44", "i128")) { str =>
            str.asTree[Expr] shouldBe ExprType(TypeSInt(Expr(str.tail.toInt)))
          }
        }

        "parametrized integers" - {
          "1D unsigned" in {
            "uint(N)".asTree[Expr] shouldBe ExprType(TypeUInt(ExprRef(Ident("N"))))
          }

          "2D unsigned" in {
            "uint(8, 2)".asTree[Expr] shouldBe ExprType(TypeVector(TypeUInt(Expr(2)), Expr(8)))
          }

          "3D unsigned" in {
            "uint(4, 8, 2)".asTree[Expr] shouldBe {
              ExprType(TypeVector(TypeVector(TypeUInt(Expr(2)), Expr(8)), Expr(4)))
            }
          }

          "1D signed" in {
            "int(N)".asTree[Expr] shouldBe ExprType(TypeSInt(ExprRef(Ident("N"))))
          }

          "2D signed" in {
            "int(8, 2)".asTree[Expr] shouldBe ExprType(TypeVector(TypeSInt(Expr(2)), Expr(8)))
          }

          "3D signed" in {
            "int(4, 8, 2)".asTree[Expr] shouldBe {
              ExprType(TypeVector(TypeVector(TypeSInt(Expr(2)), Expr(8)), Expr(4)))
            }
          }
        }

        "void" in {
          "void".asTree[Expr] shouldBe ExprType(TypeVoid)
        }
      }

      "entity contents" - {
        "empty" in {
          "fsm a {}".asTree[Entity] shouldBe {
            Entity(Ident("a"), Nil, Nil, Nil, Nil, Nil, Nil, Nil, Map())
          }
        }

        "declaration" in {
          """|network b {
             |  in bool p_in;
             |}""".asTree[Entity] shouldBe {
            Entity(
              Ident("b"),
              List(Decl(Ident("p_in"), TypeIn(TypeUInt(Expr(1)), FlowControlTypeNone), None)),
              Nil,
              Nil,
              Nil,
              Nil,
              Nil,
              Nil,
              Map()
            )
          }
        }

        "instance without parameters" in {
          """|network c {
                       |  i = new j();
                       |}""".asTree[Entity] shouldBe {
            Entity(
              Ident("c"),
              Nil,
              List(Instance(Ident("i"), Ident("j"), Nil, Nil)),
              Nil,
              Nil,
              Nil,
              Nil,
              Nil,
              Map()
            )
          }
        }

        "instance with parameters" in {
          """|network d {
                       |  i = new j(A=2, B=3);
                       |}""".asTree[Entity] shouldBe {
            Entity(
              Ident("d"),
              Nil,
              List(Instance(Ident("i"), Ident("j"), List("A", "B"), List(Expr(2), Expr(3)))),
              Nil,
              Nil,
              Nil,
              Nil,
              Nil,
              Map()
            )
          }
        }

        "single connection" in {
          """|network e {
                       |  i.a -> j.b;
                       |}""".asTree[Entity] shouldBe {
            Entity(
              Ident("e"),
              Nil,
              Nil,
              List(Connect(ExprSelect(ExprRef(Ident("i")), "a"),
                           List(ExprSelect(ExprRef(Ident("j")), "b")))),
              Nil,
              Nil,
              Nil,
              Nil,
              Map()
            )
          }
        }

        "multiple connections" in {
          """|network f {
                       |  i.a -> j.b, k.c;
                       |}""".asTree[Entity] shouldBe {
            Entity(
              Ident("f"),
              Nil,
              Nil,
              List(
                Connect(
                  ExprSelect(ExprRef(Ident("i")), "a"),
                  List(ExprSelect(ExprRef(Ident("j")), "b"), ExprSelect(ExprRef(Ident("k")), "c"))
                )),
              Nil,
              Nil,
              Nil,
              Nil,
              Map()
            )
          }
        }

        "function" in {
          """|fsm g {
                       |  void main() {}
                       |}""".asTree[Entity] shouldBe {
            Entity(
              Ident("g"),
              Nil,
              Nil,
              Nil,
              List(Function(Ident("main"), Nil)),
              Nil,
              Nil,
              Nil,
              Map()
            )
          }
        }

        "fence block" in {
          """|fsm g {
                       |  fence {
                       |    a = 1;
                       |  }
                       |}""".asTree[Entity] shouldBe {
            Entity(
              Ident("g"),
              Nil,
              Nil,
              Nil,
              Nil,
              Nil,
              List(StmtBlock(List(StmtAssign(ExprRef(Ident("a")), Expr(1))))),
              Nil,
              Map()
            )
          }
        }

        "nested fsm without auto instantiation" in {
          """|network  h {
                       |  fsm i {}
                       |}""".asTree[Entity] shouldBe {
            Entity(
              Ident("h"),
              Nil,
              Nil,
              Nil,
              Nil,
              Nil,
              Nil,
              List(Entity(Ident("i"), Nil, Nil, Nil, Nil, Nil, Nil, Nil, Map())),
              Map()
            )
          }
        }

        "nested fsm with auto instantiation" in {
          """|network  h2 {
                       |  new fsm i {}
                       |}""".asTree[Entity] shouldBe {
            Entity(
              Ident("h2"),
              Nil,
              List(Instance(Ident("i"), Ident("i"), Nil, Nil)),
              Nil,
              Nil,
              Nil,
              Nil,
              List(Entity(Ident("i"), Nil, Nil, Nil, Nil, Nil, Nil, Nil, Map())),
              Map()
            )
          }
        }

        "verbatim verilog" in {
          """|fsm i {
                       |  verbatim verilog {
                       |    +-/* comment */ {{{}}}
                       |  }
                       |}""".asTree[Entity] shouldBe {
            Entity(
              Ident("i"),
              Nil,
              Nil,
              Nil,
              Nil,
              Nil,
              Nil,
              Nil,
              Map("verilog" -> "\n    +-/* comment */ {{{}}}\n  ")
            )
          }
        }

        "verbatim other" in {
          """|fsm j {
                       |  verbatim other {
                       |    +-/* comment */ {{{}}}
                       |  }
                       |}""".asTree[Entity] shouldBe {
            Entity(
              Ident("j"),
              Nil,
              Nil,
              Nil,
              Nil,
              Nil,
              Nil,
              Nil,
              Map("other" -> "\n    +-/* comment */ {{{}}}\n  ")
            )
          }
        }

        "multiple verbatim" in {
          """|fsm k {
                       |  verbatim verilog {
                       |    first
                       |  }
                       |
                       |  verbatim verilog {
                       |second
                       |  }
                       |}""".asTree[Entity] shouldBe {
            Entity(
              Ident("k"),
              Nil,
              Nil,
              Nil,
              Nil,
              Nil,
              Nil,
              Nil,
              Map("verilog" -> "\n    first\n  \n\nsecond\n  ")
            )
          }
        }

      }

      "blocks" - {
        "empty block" in {
          "{}".asTree[Stmt] shouldBe StmtBlock(Nil)
        }

        "single statement block" in {
          "{ 1; }".asTree[Stmt] shouldBe StmtBlock(List(StmtExpr(Expr(1))))
        }

        "multiple statement block" in {
          "{ 1; 2; 3; }".asTree[Stmt] shouldBe {
            StmtBlock(List(StmtExpr(Expr(1)), StmtExpr(Expr(2)), StmtExpr(Expr(3))))
          }
        }

      }

      "statements" - {
        "block as statement" in {
          "{}".asTree[Stmt] shouldBe StmtBlock(Nil)
        }

        "branching" - {
          "if without else" in {
            "if (1) a;".asTree[Stmt] shouldBe StmtIf(Expr(1), StmtExpr(ExprRef(Ident("a"))), None)
          }

          "if with else" in {
            "if (1) fence; else return;".asTree[Stmt] shouldBe StmtIf(Expr(1),
                                                                      StmtFence(),
                                                                      Some(StmtReturn()))
          }

          "case without default" in {
            """|case (1) {
               | 1: a;
               | 2: b;
               |}
               |""".stripMargin.asTree[Stmt] shouldBe {
              StmtCase(
                Expr(1),
                List(
                  CaseClause(List(Expr(1)), StmtExpr(ExprRef(Ident("a")))),
                  CaseClause(List(Expr(2)), StmtExpr(ExprRef(Ident("b"))))
                ),
                List()
              )
            }
          }

          "case with default" in {
            """|case (1) {
               | default: c;
               |}
               |""".stripMargin.asTree[Stmt] shouldBe {
              StmtCase(
                Expr(1),
                Nil,
                List(StmtBlock(List(StmtExpr(ExprRef(Ident("c"))))))
              )
            }
          }

          "case with multiple labels" in {
            """|case (1) {
               | 1: c;
               | 2, 3: d;
               |}
               |""".stripMargin.asTree[Stmt] shouldBe {
              StmtCase(
                Expr(1),
                List(
                  CaseClause(List(Expr(1)), StmtExpr(ExprRef(Ident("c")))),
                  CaseClause(List(Expr(2), Expr(3)), StmtExpr(ExprRef(Ident("d"))))
                ),
                List()
              )
            }
          }
        }

        "loops" - {
          "loop" in {
            """|loop {
               |  1;
               |}""".asTree[Stmt] shouldBe StmtLoop(List(StmtExpr(Expr(1))))
          }

          "while" in {
            """|while (a) {
               |  fence;
               |}""".asTree[Stmt] shouldBe StmtWhile(ExprRef(Ident("a")), List(StmtFence()))
          }

          "do" in {
            """|do {
               | fence;
               |} while(b);""".asTree[Stmt] shouldBe StmtDo(ExprRef(Ident("b")), List(StmtFence()))
          }

          "for" - {
            "empty" in {
              "for(;;){}".asTree[Stmt] shouldBe StmtFor(Nil, None, Nil, Nil)
            }

            "with single init assign" in {
              """|for (a=2;a;a--) {
                 |  2;
                 |}""".asTree[Stmt] shouldBe {
                StmtFor(
                  List(StmtAssign(ExprRef(Ident("a")), Expr(2))),
                  Some(ExprRef(Ident("a"))),
                  List(StmtPost(ExprRef(Ident("a")), "--")),
                  List(StmtExpr(Expr(2)))
                )

              }
            }

            "with single init decl" in {
              """|for (i8 a=2;a;a--) {
                 |  2;
                 |}""".stripMargin.asTree[Stmt] shouldBe {
                StmtFor(
                  List(StmtDecl(Decl(Ident("a"), TypeSInt(Expr(8)), Some(Expr(2))))),
                  Some(ExprRef(Ident("a"))),
                  List(StmtPost(ExprRef(Ident("a")), "--")),
                  List(StmtExpr(Expr(2)))
                )
              }
            }

            "with multiple init" in {
              """|for (i8 a=2, b=1;;) {
                 |}""".stripMargin.asTree[Stmt] shouldBe {
                StmtFor(
                  List(
                    StmtDecl(Decl(Ident("a"), TypeSInt(Expr(8)), Some(Expr(2)))),
                    StmtAssign(ExprRef(Ident("b")), Expr(1))
                  ),
                  None,
                  Nil,
                  Nil
                )
              }
            }

            "with multiple step" in {
              """|for (;;a++, b--) {
                 |}""".stripMargin.asTree[Stmt] shouldBe {
                StmtFor(
                  Nil,
                  None,
                  List(
                    StmtPost(ExprRef(Ident("a")), "++"),
                    StmtPost(ExprRef(Ident("b")), "--")
                  ),
                  Nil
                )
              }
            }
          }
        }

        "let" - {
          "single assignment" in {
            "let (a=2) loop {}".asTree[Stmt] shouldBe {
              StmtLet(List(StmtAssign(ExprRef(Ident("a")), Expr(2))), StmtLoop(Nil))
            }
          }

          "single declaration" in {
            "let (i2 a=1) loop {}".asTree[Stmt] shouldBe {
              StmtLet(List(StmtDecl(Decl(Ident("a"), TypeSInt(Expr(2)), Some(Expr(1))))),
                      StmtLoop(Nil))
            }
          }

          "multiple initializers" in {
            "let (i2 a=b, c=a) loop {}".asTree[Stmt] shouldBe {
              StmtLet(
                List(
                  StmtDecl(Decl(Ident("a"), TypeSInt(Expr(2)), Some(ExprRef(Ident("b"))))),
                  StmtAssign(ExprRef(Ident("c")), ExprRef(Ident("a")))
                ),
                StmtLoop(Nil)
              )

            }
          }
        }

        "fence" in {
          "fence;".asTree[Stmt] shouldBe StmtFence()
        }

        "break" in {
          "break;".asTree[Stmt] shouldBe StmtBreak()
        }

        "return" in {
          "return;".asTree[Stmt] shouldBe StmtReturn()
        }

        "goto" in {
          "goto foo;".asTree[Stmt] shouldBe StmtGoto(Ident("foo"))
        }

        // TODO: assignments
        "assignments" - {
          "simple" in {
            "a = 1;".asTree[Stmt] shouldBe StmtAssign(ExprRef(Ident("a")), Expr(1))
          }

          "update +=" in {
            "b += 2;".asTree[Stmt] shouldBe StmtUpdate(ExprRef(Ident("b")), "+", Expr(2))
          }

          "update <<=" in {
            "c <<= 3;".asTree[Stmt] shouldBe StmtUpdate(ExprRef(Ident("c")), "<<", Expr(3))
          }

          "postfix ++" in {
            "d++;".asTree[Stmt] shouldBe StmtPost(ExprRef(Ident("d")), "++")
          }

          "postfix --" in {
            "e--;".asTree[Stmt] shouldBe StmtPost(ExprRef(Ident("e")), "--")
          }
        }

        "expressions in statement position" - {
          "identifier" in {
            "a;".asTree[Stmt] shouldBe StmtExpr(ExprRef(Ident("a")))
          }

          "call" in {
            "b();".asTree[Stmt] shouldBe StmtExpr(ExprCall(ExprRef(Ident("b")), Nil))
          }
        }

        "declaration statements" - {
          "scalar without initializer" in {
            "u2 a;".asTree[Stmt] shouldBe StmtDecl(Decl(Ident("a"), TypeUInt(Expr(2)), None))
          }

          "scalar with initializer" in {
            "i2 b = 3;".asTree[Stmt] shouldBe {
              StmtDecl(Decl(Ident("b"), TypeSInt(Expr(2)), Some(Expr(3))))
            }
          }
        }

        "$ comment" in {
          "$(\"hello\");".asTree[Stmt] shouldBe StmtDollarComment("hello")
        }

        "read statement" in {
          "read;".asTree[Stmt] shouldBe StmtRead()
        }

        "write statement" in {
          "write;".asTree[Stmt] shouldBe StmtWrite()
        }

      }

      "expressions" - {

        "literals" - {
          "true" in {
            "true".asTree[Expr] shouldBe ExprInt(false, 1, 1)
          }

          "false" in {
            "false".asTree[Expr] shouldBe ExprInt(false, 1, 0)
          }

          "integer" in {
            "42".asTree[Expr] shouldBe ExprNum(true, 42)
          }

          "unsized unsigned binary integer" in {
            "'b11".asTree[Expr] shouldBe ExprNum(false, 3)
          }

          "unsized unsigned decimal integer" in {
            "'d11".asTree[Expr] shouldBe ExprNum(false, 11)
          }

          "unsized unsigned hex integer" in {
            "'h11".asTree[Expr] shouldBe ExprNum(false, 17)
          }

          "unsized signed binary integer" in {
            "'sb11".asTree[Expr] shouldBe ExprNum(true, 3)
          }

          "unsized signed decimal integer" in {
            "'sd11".asTree[Expr] shouldBe ExprNum(true, 11)
          }

          "unsized signed hex integer" in {
            "'sh11".asTree[Expr] shouldBe ExprNum(true, 17)
          }

          "sized unsigned binary integer" in {
            "12'b11".asTree[Expr] shouldBe ExprInt(false, 12, 3)
          }

          "sized unsigned decimal integer" in {
            "13'd11".asTree[Expr] shouldBe ExprInt(false, 13, 11)
          }

          "sized unsigned hex integer" in {
            "14'h11".asTree[Expr] shouldBe ExprInt(false, 14, 17)
          }

          "sized signed binary integer" in {
            "15'sb11".asTree[Expr] shouldBe ExprInt(true, 15, 3)
          }

          "sized signed decimal integer" in {
            "16'sd11".asTree[Expr] shouldBe ExprInt(true, 16, 11)
          }

          "sized signed hex integer" in {
            "17'sh11".asTree[Expr] shouldBe ExprInt(true, 17, 17)
          }

          "string" in {
            "\"foo\"".asTree[Expr] shouldBe ExprStr("foo")
          }
        }

        "simple" - {

          "bracket" in {
            "(((1)))".asTree[Expr] shouldBe Expr(1)
          }

          "call with no arguments" in {
            "a()".asTree[Expr] shouldBe ExprCall(ExprRef(Ident("a")), Nil)
          }

          "call with 1 argument" in {
            "b(2)".asTree[Expr] shouldBe ExprCall(ExprRef(Ident("b")), List(Expr(2)))
          }

          "call with 2 arguments" in {
            "c(d, e)".asTree[Expr] shouldBe {
              ExprCall(ExprRef(Ident("c")), List(ExprRef(Ident("d")), ExprRef(Ident("e"))))
            }
          }

          for (op <- List("+", "-", "~", "!", "&", "|", "^")) {
            s"unary ${op}" in {
              s"${op}2".asTree[Expr] shouldBe ExprUnary(op, Expr(2))
            }
          }

          for (op <- List("*",
                          "/",
                          "%",
                          "+",
                          "-",
                          "<<",
                          ">>",
                          ">>>",
                          "<<<",
                          ">",
                          ">=",
                          "<",
                          "<=",
                          "==",
                          "!=",
                          "&",
                          "^",
                          "|",
                          "&&",
                          "||")) {
            s"binary $op" in {
              s"4 ${op} 3".asTree[Expr] shouldBe ExprBinary(Expr(4), op, Expr(3))
            }
          }

          "ternary" in {
            "1 ? 2 : 3".asTree[Expr] shouldBe ExprTernary(Expr(1), Expr(2), Expr(3))
          }

          "repetition" in {
            "{N{a}}".asTree[Expr] shouldBe ExprRep(ExprRef(Ident("N")), ExprRef(Ident("a")))
          }

          "concatenation" in {
            "{0, 1, 2}".asTree[Expr] shouldBe ExprCat(List(Expr(0), Expr(1), Expr(2)))
          }

          "index 1x" in {
            "a[0]".asTree[Expr] shouldBe ExprIndex(ExprRef(Ident("a")), Expr(0))
          }

          "index 2x" in {
            "a[0][2]".asTree[Expr] shouldBe {
              ExprIndex(ExprIndex(ExprRef(Ident("a")), Expr(0)), Expr(2))
            }
          }

          "slice 1x" in {
            "b[1:0]".asTree[Expr] shouldBe ExprSlice(ExprRef(Ident("b")), Expr(1), ":", Expr(0))
          }

          "slice 2x" in {
            "b[2+:0][1-:1]".asTree[Expr] should matchPattern {
              case ExprSlice(ExprSlice(ExprRef(Ident("b")), Expr(2), "+:", Expr(0)),
                             Expr(1),
                             "-:",
                             Expr(1)) =>
            }
          }

          "select 1x" in {
            "a.b".asTree[Expr] shouldBe ExprSelect(ExprRef(Ident("a")), "b")
          }

          "select 2x" in {
            "a.b.c".asTree[Expr] shouldBe ExprSelect(ExprSelect(ExprRef(Ident("a")), "b"), "c")
          }

          "@ call" in {
            "@zx(0, a)".asTree[Expr] shouldBe ExprAtCall("zx", List(Expr(0), ExprRef(Ident("a"))))
          }

          "$ call" in {
            "$clog2(a)".asTree[Expr] shouldBe ExprDollarCall("clog2", List(ExprRef(Ident("a"))))
          }

          "identifier" in {
            "foo".asTree[Expr] shouldBe ExprRef(Ident("foo"))
          }

          "type" in {
            "i8".asTree[Expr] shouldBe ExprType(TypeSInt(Expr(8)))
          }
        }

        "honouring precedence" - {
          "1 + 2 * 3" in {
            "1 + 2 * 3".asTree[Expr] shouldBe {
              Expr(1) + ExprBinary(Expr(2), "*", Expr(3))
            }
          }
          "1 + 2 + 3" in {
            "1 + 2 + 3".asTree[Expr] shouldBe {
              ExprBinary(Expr(1), "+", Expr(2)) + Expr(3)
            }
          }

          "a.b && a.c" in {
            "a.b && a.c".asTree[Expr] shouldBe {
              ExprSelect(ExprRef(Ident("a")), "b") && ExprSelect(ExprRef(Ident("a")), "c")
            }
          }

          "a.b && a.c == 1" in {
            "a.b && a.c == 1".asTree[Expr] shouldBe {
              ExprSelect(ExprRef(Ident("a")), "b") &&
              ExprBinary(ExprSelect(ExprRef(Ident("a")), "c"), "==", Expr(1))
            }
          }

          "a.b && a[0]" in {
            "a.b && a[0]".asTree[Expr] shouldBe {
              ExprSelect(ExprRef(Ident("a")), "b") && ExprIndex(ExprRef(Ident("a")), 0)
            }
          }

          "a.b && a[1:0]" in {
            "a.b && a[1:0]".asTree[Expr] shouldBe {
              ExprSelect(ExprRef(Ident("a")), "b") && ExprSlice(ExprRef(Ident("a")), 1, ":", 0)
            }
          }

          "a.b[1]" in {
            "a.b[1]".asTree[Expr] shouldBe {
              ExprIndex(ExprSelect(ExprRef(Ident("a")), "b"), 1)
            }
          }
          // TODO: complete all precedence checks
        }
      }

    }

    /////////////////////////////////////////////////////////////////////////////
    // Locations
    /////////////////////////////////////////////////////////////////////////////

    "should assign correct locations to tree nodes" in {

      val tree = """|fsm foo {
                    |  void main() {
                    |    bar i;
                    |    loop { }
                    |  }
                    |}""".asTree[Entity]

      inside(tree) {
        case entity: Entity =>
          entity.loc.line shouldBe 1
          inside(entity.functions.loneElement) {
            case function: Function =>
              function.loc.line shouldBe 2
              inside(function.body(0)) {
                case stmtDecl: StmtDecl =>
                  stmtDecl.loc.line shouldBe 3
                  stmtDecl.decl.loc.line shouldBe 3
                  stmtDecl.decl.ref.loc.line shouldBe 3
                  inside(stmtDecl.decl.kind) {
                    case TypeRef(ident: Ident) =>
                      ident.loc.line shouldBe 3
                  }
              }
              inside(function.body(1)) {
                case stmtLoop: StmtLoop =>
                  stmtLoop.loc.line shouldBe 4
                  stmtLoop.body shouldBe empty
              }
          }
      }

      cc.messages shouldBe empty
    }

  }

}

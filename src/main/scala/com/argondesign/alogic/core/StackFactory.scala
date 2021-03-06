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
// Factory to build stack entities
////////////////////////////////////////////////////////////////////////////////
package com.argondesign.alogic.core

import com.argondesign.alogic.ast.Trees._
import com.argondesign.alogic.core.FlowControlTypes.FlowControlTypeNone
import com.argondesign.alogic.core.StorageTypes._
import com.argondesign.alogic.core.Types._
import com.argondesign.alogic.lib.Math
import com.argondesign.alogic.typer.TypeAssigner

object StackFactory {

  /*

  // Hardware stack interface

  stack<i8>(10) s;

  s.push(addr); // i8 => void
  s.pop();      // void => i8
  s.set(addr);  // i8 => void // Same as 's = addr' if s is an automatic variable
  s.top;        // i8 // Same as 's' if s is an automatic variable
  s.full;       // bool
  s.empty;      // bool

  restrictions:
   - Can only do a single push, pop, or set in the same cycle, compiler should err otherwise
   - .push() when full is error
   - .pop() when empty is error
   - .top when empty is error

  // Function call/return handling ('rs' is for 'return-stack':

  foo();    // maps to rs.push(return-state); goto foo;
  goto foo; // nothing to do here, just goto foo;
  return;   // maps to goto rs.pop();

  // Function argument handling

  At call site: arg.push(value)
  At exit: arg.pop()

  // Function local variable handling

  At definition of variable: local.push(initializer)
  At death/exit from function: local.pop()

  // Hardware interface:

  _en
  _d
  _q

  _push
  _pop
  _full
  _empty

  at beginning:
  _en = 1'b0

   */

  // Build an entity similar to the following Alogic FSM to be used as a
  // 1 entry stack implementation.
  //
  // fsm stack_1 {
  //   in bool en;
  //
  //   in TYPE d;
  //   in bool push;
  //   in bool pop;
  //
  //   out wire TYPE q;
  //   out wire bool empty;
  //   out wire bool full;
  //
  //   bool valid = false;
  //
  //   TYPE storage;
  //
  //   void main() {
  //     if (en) {
  //       storage = d;
  //       valid = ~pop & (valid | push);
  //     }
  //     fence;
  //   }
  //
  //  ~valid -> empty;
  //  valid -> full;
  //  storage -> q;
  // }
  private def buildStack1(
      name: String,
      loc: Loc,
      kind: Type
  )(
      implicit cc: CompilerContext
  ): EntityLowered = {
    val fcn = FlowControlTypeNone
    val stw = StorageTypeWire

    val bool = TypeUInt(TypeAssigner(Expr(1) withLoc loc))

    val enSymbol = cc.newTermSymbol("en", loc, TypeIn(bool, fcn))

    val pusSymbol = cc.newTermSymbol("push", loc, TypeIn(bool, fcn))
    val popSymbol = cc.newTermSymbol("pop", loc, TypeIn(bool, fcn))
    val dSymbol = cc.newTermSymbol("d", loc, TypeIn(kind, fcn))

    val empSymbol = cc.newTermSymbol("empty", loc, TypeOut(bool, fcn, stw))
    val fulSymbol = cc.newTermSymbol("full", loc, TypeOut(bool, fcn, stw))
    val qSymbol = cc.newTermSymbol("q", loc, TypeOut(kind, fcn, stw))

    val valSymbol = cc.newTermSymbol("valid", loc, bool)
    val stoSymbol = cc.newTermSymbol("storage", loc, kind)

    val enRef = ExprRef(enSymbol)

    val pusRef = ExprRef(pusSymbol)
    val popRef = ExprRef(popSymbol)
    val dRef = ExprRef(dSymbol)

    val empRef = ExprRef(empSymbol)
    val fulRef = ExprRef(fulSymbol)
    val qRef = ExprRef(qSymbol)

    val valRef = ExprRef(valSymbol)

    val stoRef = ExprRef(stoSymbol)

    val statements = List(
      StmtIf(enRef,
             StmtBlock(
               List(
                 StmtAssign(stoRef, dRef),
                 StmtAssign(valRef, ~popRef & (valRef | pusRef))
               )),
             None)
    )

    val ports = List(
      enSymbol,
      dSymbol,
      pusSymbol,
      popSymbol,
      qSymbol,
      empSymbol,
      fulSymbol
    )

    val symbols = valSymbol :: stoSymbol :: ports

    val decls = symbols map { symbol =>
      val init = symbol match {
        case `valSymbol` => Some(ExprInt(false, 1, 0))
        case _           => None
      }
      Decl(symbol, init)
    }

    val connects = List(
      Connect(~valRef, List(empRef)),
      Connect(valRef, List(fulRef)),
      Connect(stoRef, List(qRef))
    )

    val eKind = TypeEntity(name, ports, Nil)
    val entitySymbol = cc.newTypeSymbol(name, loc, eKind)
    entitySymbol.attr.variant set "fsm"
    entitySymbol.attr.highLevelKind set eKind
    val entity = EntityLowered(entitySymbol, decls, Nil, connects, statements, Map())
    entity regularize loc
  }

  // Build an entity similar to the following Alogic FSM to be used as an
  // N entry stack implementation with N >= 2.
  //
  // fsm stack_N {
  //   const DEPTH; // > 1
  //
  //   in bool en;
  //
  //   in TYPE d;
  //   in bool push;
  //   in bool pop;
  //
  //   out wire TYPE q;
  //   out bool empty = true;
  //   out bool full = false;
  //
  //   TYPE storage[DEPTH];
  //
  //   uint($clog2(DEPTH)) ptr = 0; // Ptr to current entry
  //
  //   state main {
  //     if (en) {
  //       if (pop) {
  //         empty = ptr == 0;
  //         full = false;
  //         ptr = ptr - ~empty;
  //       } else {
  //         ptr = ptr + (~empty & ~full & push);
  //         storage.write(ptr, d);
  //         empty = empty & ~push;
  //         full = ptr == DEPTH - 1;
  //       }
  //     }
  //     fence;
  //   }
  //
  //   storage[ptr] -> q;
  // }
  private def buildStackN(
      name: String,
      loc: Loc,
      kind: Type,
      depth: Int
  )(
      implicit cc: CompilerContext
  ): EntityLowered = {
    require(depth >= 2)

    val fcn = FlowControlTypeNone
    val stw = StorageTypeWire
    val str = StorageTypeReg

    val bool = TypeUInt(TypeAssigner(Expr(1) withLoc loc))

    val ptrWidth = Math.clog2(depth)

    val enSymbol = cc.newTermSymbol("en", loc, TypeIn(bool, fcn))

    val pusSymbol = cc.newTermSymbol("push", loc, TypeIn(bool, fcn))
    val popSymbol = cc.newTermSymbol("pop", loc, TypeIn(bool, fcn))
    val dSymbol = cc.newTermSymbol("d", loc, TypeIn(kind, fcn))

    val empSymbol = cc.newTermSymbol("empty", loc, TypeOut(bool, fcn, str))
    val fulSymbol = cc.newTermSymbol("full", loc, TypeOut(bool, fcn, str))
    val qSymbol = cc.newTermSymbol("q", loc, TypeOut(kind, fcn, stw))

    val stoKind = TypeArray(kind, ExprNum(false, depth) regularize loc)
    val stoSymbol = cc.newTermSymbol("storage", loc, stoKind)
    val ptrKind = TypeUInt(Expr(ptrWidth) regularize loc)
    val ptrSymbol = cc.newTermSymbol("ptr", loc, ptrKind)

    val enRef = ExprRef(enSymbol)

    val pusRef = ExprRef(pusSymbol)
    val popRef = ExprRef(popSymbol)
    val dRef = ExprRef(dSymbol)

    val empRef = ExprRef(empSymbol)
    val fulRef = ExprRef(fulSymbol)
    val qRef = ExprRef(qSymbol)

    val stoRef = ExprRef(stoSymbol)
    val ptrRef = ExprRef(ptrSymbol)

    def zextPtrWidth(bool: Expr): Expr = {
      if (ptrWidth == 1) {
        bool
      } else {
        ExprCat(List(ExprInt(false, ptrWidth - 1, 0), bool))
      }
    }

    val statements = List(
      StmtIf(
        enRef,
        StmtBlock(
          List(
            StmtIf(
              popRef,
              StmtBlock(List(
                StmtAssign(empRef, ExprBinary(ptrRef, "==", ExprInt(false, ptrWidth, 0))),
                StmtAssign(fulRef, ExprInt(false, 1, 0)),
                StmtAssign(ptrRef, ptrRef - zextPtrWidth(~empRef))
              )),
              Some(
                StmtBlock(List(
                  StmtAssign(ptrRef, ptrRef + zextPtrWidth(~empRef & ~fulRef & pusRef)),
                  StmtExpr(ExprCall(stoRef select "write", List(ptrRef, dRef))),
                  StmtAssign(empRef, empRef & ~pusRef),
                  StmtAssign(fulRef, ExprBinary(ptrRef, "==", ExprInt(false, ptrWidth, depth - 1)))
                ))
              )
            )
          )),
        None
      )
    )

    val ports = List(
      enSymbol,
      dSymbol,
      pusSymbol,
      popSymbol,
      qSymbol,
      empSymbol,
      fulSymbol
    )

    val symbols = stoSymbol :: ptrSymbol :: ports

    val decls = symbols map { symbol =>
      val init = symbol match {
        case `empSymbol` => Some(ExprInt(false, 1, 1))
        case `fulSymbol` => Some(ExprInt(false, 1, 0))
        case `ptrSymbol` => Some(ExprInt(false, ptrWidth, 0))
        case _           => None
      }
      Decl(symbol, init)
    }

    val connects = List(
      Connect(ExprIndex(stoRef, ptrRef), List(qRef))
    )

    val eKind = TypeEntity(name, ports, Nil)
    val entitySymbol = cc.newTypeSymbol(name, loc, eKind)
    entitySymbol.attr.variant set "fsm"
    entitySymbol.attr.highLevelKind set eKind
    val entity = EntityLowered(entitySymbol, decls, Nil, connects, statements, Map())
    entity regularize loc
  }

  def apply(
      name: String,
      loc: Loc,
      kind: Type,
      depth: Expr
  )(
      implicit cc: CompilerContext
  ): EntityLowered = {
    require(kind.isPacked)
    require(kind != TypeVoid)

    depth.value match {
      case Some(v) if v == 1 => buildStack1(name, loc, kind)
      case Some(v)           => buildStackN(name, loc, kind, v.toInt)
      case None              => cc.ice(loc, "Stack with non-computable dept")
    }
  }

}

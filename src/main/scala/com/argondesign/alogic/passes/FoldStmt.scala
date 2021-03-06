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
// Fold statements
////////////////////////////////////////////////////////////////////////////////

package com.argondesign.alogic.passes

import com.argondesign.alogic.analysis.StaticEvaluation
import com.argondesign.alogic.ast.TreeTransformer
import com.argondesign.alogic.ast.Trees._
import com.argondesign.alogic.core.Bindings
import com.argondesign.alogic.core.CompilerContext
import com.argondesign.alogic.lib.Stack
import com.argondesign.alogic.util.FollowedBy

final class FoldStmt(implicit cc: CompilerContext) extends TreeTransformer with FollowedBy {

  override def skip(tree: Tree): Boolean = tree match {
    case entity: EntityLowered => entity.statements.isEmpty
    case _: Expr               => true
    case _: Connect            => true
    case _: Instance           => true
    case _                     => false
  }

  private[this] var bindingsMap: Map[Int, Bindings] = _

  private[this] val bindings = Stack[Bindings]()

  override def enter(tree: Tree): Unit = tree match {
    case entity: EntityLowered => {
      bindingsMap = StaticEvaluation(StmtBlock(entity.statements))._1
    }

    case stmt: Stmt => {
      // TODO: should not have to do orElse
      bindings.push(bindingsMap.getOrElse(stmt.id, Bindings.empty))
    }

    case _ =>
  }

  override def transform(tree: Tree): Tree = {
    val result = tree match {
      case StmtIf(cond, thenStmt, Some(elseStmt)) => {
        (cond given bindings.top).value match {
          case Some(v) if v != 0 => thenStmt
          case Some(v) if v == 0 => elseStmt
          case None              => tree
        }
      }

      case StmtIf(cond, thenStmt, None) => {
        (cond given bindings.top).value match {
          case Some(v) if v != 0 => thenStmt
          case Some(v) if v == 0 => StmtBlock(Nil) regularize tree.loc
          case None              => tree
        }
      }

      case StmtStall(cond) => {
        (cond given bindings.top).value match {
          case Some(v) if v != 0 => StmtBlock(Nil) regularize tree.loc
          case Some(v) if v == 0 => {
            cc.error(tree, "Stall condition is always true")
            tree
          }
          case None => tree
        }
      }

      case _ => tree
    }

    if (tree.isInstanceOf[Stmt]) {
      bindings.pop()
    }

    result
  }

  override def finalCheck(tree: Tree): Unit = {
    assert(bindings.isEmpty)
  }
}

object FoldStmt extends TreeTransformerPass {
  val name = "fold-stms"
  def create(implicit cc: CompilerContext) = new FoldStmt
}

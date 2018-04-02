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
// Build a Decl AST from an Antlr4 parse tree
////////////////////////////////////////////////////////////////////////////////

package com.argondesign.alogic.antlr

import com.argondesign.alogic.antlr.AlogicParser._
import com.argondesign.alogic.antlr.AntlrConverters._
import com.argondesign.alogic.ast.Trees.Decl
import com.argondesign.alogic.core.CompilerContext
import com.argondesign.alogic.core.Types._

object DeclBuilder extends BaseBuilder[DeclContext, Decl] {

  def apply(ctx: DeclContext)(implicit cc: CompilerContext): Decl = {
    object Visitor extends AlogicScalarVisitor[Decl] {

      // Attach initializers
      override def visitDecl(ctx: DeclContext) = {
        val init = Option(ctx.expr) map { ExprBuilder(_) }
        val decl = visit(ctx.declbase)
        if (init.isDefined) {
          decl.copy(init = init) withLoc ctx.loc.copy(point = decl.loc.point)
        } else {
          decl
        }
      }

      // Simple decls
      override def visitDeclVar(ctx: DeclVarContext) = {
        val kind = TypeBuilder(ctx.kind)
        Decl(ctx.IDENTIFIER.toIdent, kind, None) withLoc ctx.loc
      }

      override def visitDeclArr(ctx: DeclArrContext) = {
        val sizes = ExprBuilder(ctx.expr).reverse
        val kind = sizes.foldLeft[Type](TypeBuilder(ctx.kind)) { (elem, size) =>
          TypeArray(elem, size)
        }
        Decl(ctx.IDENTIFIER.toIdent, kind, None) withLoc {
          ctx.loc.copy(point = ctx.IDENTIFIER.getStartIndex)
        }
      }

      // Entity decls
      override def visitDeclOut(ctx: DeclOutContext) = {
        val ident = ctx.IDENTIFIER.toIdent
        val underlying = TypeBuilder(ctx.kind)
        val fcType = FlowControlTypeBuilder(ctx.flow_control_type)
        val storage = StorageTypeBuilder(ctx.storage_type)
        val kind = TypeOut(underlying, fcType, storage)
        Decl(ident, kind, None) withLoc {
          ctx.loc.copy(point = ctx.IDENTIFIER.getStartIndex)
        }
      }

      override def visitDeclIn(ctx: DeclInContext) = {
        val underlying = TypeBuilder(ctx.kind)
        val fcType = FlowControlTypeBuilder(ctx.flow_control_type)
        val kind = TypeIn(underlying, fcType)
        Decl(ctx.IDENTIFIER.toIdent, kind, None) withLoc {
          ctx.loc.copy(point = ctx.IDENTIFIER.getStartIndex)
        }
      }

      override def visitDeclParam(ctx: DeclParamContext) = {
        val underlying = TypeBuilder(ctx.kind)
        val kind = TypeParam(underlying)
        Decl(ctx.IDENTIFIER.toIdent, kind, None) withLoc {
          ctx.loc.copy(point = ctx.IDENTIFIER.getStartIndex)
        }
      }

      override def visitDeclConst(ctx: DeclConstContext) = {
        val underlying = TypeBuilder(ctx.kind)
        val kind = TypeConst(underlying)
        Decl(ctx.IDENTIFIER.toIdent, kind, None) withLoc {
          ctx.loc.copy(point = ctx.IDENTIFIER.getStartIndex)
        }
      }

      override def visitDeclPipeline(ctx: DeclPipelineContext) = {
        val underlying = TypeBuilder(ctx.kind)
        val kind = TypePipeline(underlying)
        Decl(ctx.IDENTIFIER.toIdent, kind, None) withLoc {
          ctx.loc.copy(point = ctx.IDENTIFIER.getStartIndex)
        }
      }
    }

    Visitor(ctx)
  }

}

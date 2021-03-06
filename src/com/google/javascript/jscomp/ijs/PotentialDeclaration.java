/*
 * Copyright 2017 The Closure Compiler Authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.google.javascript.jscomp.ijs;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;

import com.google.javascript.jscomp.AbstractCompiler;
import com.google.javascript.jscomp.NodeUtil;
import com.google.javascript.jscomp.Scope;
import com.google.javascript.rhino.IR;
import com.google.javascript.rhino.JSDocInfo;
import com.google.javascript.rhino.Node;
import javax.annotation.Nullable;

/**
 * Encapsulates something that could be a declaration.
 *
 * This includes:
 *   var/let/const declarations,
 *   function/class declarations,
 *   method declarations,
 *   assignments,
 *   goog.define calls,
 *   and even valueless property accesses (e.g. `/** @type {number} * / Foo.prototype.bar`)
 */
class PotentialDeclaration {
  // The fully qualified name of the declaration.
  private final String fullyQualifiedName;
  // The LHS node of the declaration.
  private final Node lhs;
  // The RHS node of the declaration, if it exists.
  private final @Nullable Node rhs;
  // The scope in which the declaration is defined.
  private final Scope scope;

  private PotentialDeclaration(String fullyQualifiedName, Node lhs, Node rhs, Scope scope) {
    this.fullyQualifiedName = checkNotNull(fullyQualifiedName);
    this.lhs = checkNotNull(lhs);
    this.rhs = rhs;
    this.scope = checkNotNull(scope);
  }

  static PotentialDeclaration fromName(Node nameNode, Scope scope) {
    checkArgument(nameNode.isQualifiedName(), nameNode);
    Node rhs = NodeUtil.getRValueOfLValue(nameNode);
    String name =
        ClassUtil.isThisProp(nameNode)
            ? ClassUtil.getPrototypeNameOfThisProp(nameNode)
            : nameNode.getQualifiedName();
    return new PotentialDeclaration(name, nameNode, rhs, scope);
  }

  static PotentialDeclaration fromMethod(Node functionNode, Scope scope) {
    checkArgument(functionNode.isFunction());
    String name = ClassUtil.getPrototypeNameOfMethod(functionNode);
    return new PotentialDeclaration(name, functionNode.getParent(), functionNode, scope);
  }

  static PotentialDeclaration fromDefine(Node callNode, Scope scope) {
    checkArgument(NodeUtil.isCallTo(callNode, "goog.define"));
    String name = callNode.getSecondChild().getString();
    Node rhs = callNode.getLastChild();
    return new PotentialDeclaration(name, callNode, rhs, scope);
  }

  String getFullyQualifiedName() {
    return fullyQualifiedName;
  }

  private Node getStatement() {
    return NodeUtil.getEnclosingStatement(lhs);
  }

  JSDocInfo getJsDoc() {
    return NodeUtil.getBestJSDocInfo(lhs);
  }

  /**
   * Remove this "potential declaration" completely.
   * Usually, this is because the same symbol has already been declared in this file.
   */
  void remove(AbstractCompiler compiler) {
    Node statement = getStatement();
    NodeUtil.deleteNode(statement, compiler);
    statement.removeChildren();
  }

  private void removeStringKeyValue(Node stringKey) {
    Node value = stringKey.getOnlyChild();
    Node replacementValue = IR.number(0).srcrefTree(value);
    stringKey.replaceChild(value, replacementValue);
  }

  /**
   * Simplify this declaration to only include what's necessary for typing.
   * Usually, this means removing the RHS and leaving a type annotation.
   */
  void simplify(AbstractCompiler compiler) {
    Node nameNode = getLhs();
    JSDocInfo jsdoc = getJsDoc();
    if (jsdoc != null && jsdoc.hasEnumParameterType()) {
      // Remove values from enums
      if (getRhs().isObjectLit() && getRhs().hasChildren()) {
        for (Node key : getRhs().children()) {
          removeStringKeyValue(key);
        }
        compiler.reportChangeToEnclosingScope(getRhs());
      }
      return;
    }
    if (NodeUtil.isNamespaceDecl(nameNode)) {
      Node objLit = getRhs();
      if (getRhs().isOr()) {
        objLit = getRhs().getLastChild().detach();
        getRhs().replaceWith(objLit);
        compiler.reportChangeToEnclosingScope(nameNode);
      }
      if (objLit.hasChildren()) {
        for (Node key : objLit.children()) {
          if (!isTypedRhs(key.getLastChild())) {
            removeStringKeyValue(key);
            JsdocUtil.updateJsdoc(compiler, key);
            compiler.reportChangeToEnclosingScope(key);
          }
        }
      }
      return;
    }
    if (nameNode.matchesQualifiedName("exports")) {
      // Replace the RHS of a default goog.module export with Unknown
      replaceRhsWithUnknown(getRhs());
      compiler.reportChangeToEnclosingScope(nameNode);
      return;
    }
    // Just completely remove the RHS, and replace with a getprop.
    Node newStatement =
        NodeUtil.newQNameDeclaration(compiler, nameNode.getQualifiedName(), null, jsdoc);
    newStatement.useSourceInfoIfMissingFromForTree(nameNode);
    Node oldStatement = getStatement();
    NodeUtil.deleteChildren(oldStatement, compiler);
    oldStatement.replaceWith(newStatement);
    compiler.reportChangeToEnclosingScope(newStatement);
  }

  static boolean isTypedRhs(Node rhs) {
    return rhs.isFunction()
        || rhs.isClass()
        || (rhs.isQualifiedName() && rhs.matchesQualifiedName("goog.abstractMethod"))
        || (rhs.isQualifiedName() && rhs.matchesQualifiedName("goog.nullFunction"));
  }

  private static void replaceRhsWithUnknown(Node rhs) {
    rhs.replaceWith(IR.cast(IR.number(0), JsdocUtil.getQmarkTypeJSDoc()).srcrefTree(rhs));
  }

  Node getLhs() {
    return lhs;
  }

  @Nullable
  Node getRhs() {
    return rhs;
  }

  Scope getScope() {
    return scope;
  }
}

// Copyright 2000-2021 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
package org.jetbrains.kotlin.idea.inspections.dfa

import com.intellij.codeInspection.dataFlow.java.inst.*
import com.intellij.codeInspection.dataFlow.lang.ir.*
import com.intellij.codeInspection.dataFlow.lang.ir.ControlFlow.DeferredOffset
import com.intellij.codeInspection.dataFlow.rangeSet.LongRangeBinOp
import com.intellij.codeInspection.dataFlow.rangeSet.LongRangeSet
import com.intellij.codeInspection.dataFlow.types.DfBooleanType
import com.intellij.codeInspection.dataFlow.types.DfIntegralType
import com.intellij.codeInspection.dataFlow.types.DfType
import com.intellij.codeInspection.dataFlow.types.DfTypes
import com.intellij.codeInspection.dataFlow.value.DfaControlTransferValue
import com.intellij.codeInspection.dataFlow.value.DfaValueFactory
import com.intellij.codeInspection.dataFlow.value.RelationType
import com.intellij.psi.PsiPrimitiveType
import com.intellij.psi.tree.TokenSet
import com.intellij.util.containers.FList
import org.jetbrains.kotlin.idea.refactoring.move.moveMethod.type
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.psi.*
import org.jetbrains.kotlin.types.KotlinType
import org.jetbrains.kotlin.types.typeUtil.isDouble
import org.jetbrains.kotlin.types.typeUtil.isFloat
import org.jetbrains.kotlin.types.typeUtil.isLong

class KtControlFlowBuilder(val factory: DfaValueFactory, val context: KtExpression) {
    // Will be used to track catch/finally blocks
    private val traps : FList<DfaControlTransferValue.Trap> = FList.emptyList()
    private val flow = ControlFlow(factory, context)
    private var broken: Boolean = false

    fun buildFlow(): ControlFlow? {
        processExpression(context)
        if (broken) return null
        addInstruction(PopInstruction()) // return value
        flow.finish()
        return flow
    }

    private fun processExpression(expr: KtExpression?) : Unit = when (expr) {
        null -> pushUnknown()
        is KtBlockExpression -> processBlock(expr)
        is KtParenthesizedExpression -> processExpression(expr.expression)
        is KtReturnExpression -> processReturnExpression(expr)
        is KtBinaryExpression -> processBinaryExpression(expr)
        is KtPrefixExpression -> processPrefixExpression(expr)
        is KtConstantExpression -> processConstantExpression(expr)
        is KtSimpleNameExpression -> processReferenceExpression(expr)
        is KtIfExpression -> processIfExpression(expr)
        is KtWhileExpression -> processWhileExpression(expr)
        is KtDoWhileExpression -> processDoWhileExpression(expr)
        is KtProperty -> processDeclaration(expr)
        else -> {
            // unsupported construct
            broken = true
        }
    }

    private fun processPrefixExpression(expr: KtPrefixExpression) {
        val operand = expr.baseExpression
        processExpression(operand)
        val anchor = KotlinExpressionAnchor(expr)
        if (operand != null) {
            val dfType = operand.getKotlinType().toDfType()
            val descriptor = KtLocalVariableDescriptor.create(operand)
            val ref = expr.operationReference.text
            if (dfType is DfIntegralType) {
                when (ref) {
                    "++", "--" -> {
                        if (descriptor != null) {
                            addInstruction(PushValueInstruction(dfType.meetRange(LongRangeSet.point(1))))
                            addInstruction(NumericBinaryInstruction(if (ref == "++") LongRangeBinOp.PLUS else LongRangeBinOp.MINUS, null))
                            addInstruction(SimpleAssignmentInstruction(anchor, factory.varFactory.createVariableValue(descriptor)))
                            return
                        }
                    }
                    "+" -> {
                        return
                    }
                    "-" -> {
                        addInstruction(PushValueInstruction(dfType.meetRange(LongRangeSet.point(0))))
                        addInstruction(SwapInstruction())
                        addInstruction(NumericBinaryInstruction(LongRangeBinOp.MINUS, anchor))
                        return
                    }
                }
            }
            if (dfType is DfBooleanType && ref == "!") {
                addInstruction(NotInstruction(anchor))
                return
            }
        }
        addInstruction(EvalUnknownInstruction(anchor, 1))
    }

    private fun processDoWhileExpression(expr: KtDoWhileExpression) {
        val offset = ControlFlow.FixedOffset(flow.instructionCount)
        processExpression(expr.body)
        addInstruction(PopInstruction())
        processExpression(expr.condition)
        addInstruction(ConditionalGotoInstruction(offset, DfTypes.TRUE))
        pushUnknown()
        addInstruction(FinishElementInstruction(expr))
    }

    private fun processWhileExpression(expr: KtWhileExpression) {
        val startOffset = ControlFlow.FixedOffset(flow.instructionCount)
        val condition = expr.condition
        processExpression(condition)
        val endOffset = DeferredOffset()
        addInstruction(ConditionalGotoInstruction(endOffset, DfTypes.FALSE, condition))
        processExpression(expr.body)
        addInstruction(PopInstruction())
        addInstruction(GotoInstruction(startOffset))
        setOffset(endOffset)
        pushUnknown()
        addInstruction(FinishElementInstruction(expr))
    }

    private fun processBlock(expr: KtBlockExpression) {
        val statements = expr.statements
        if (statements.isEmpty()) {
            pushUnknown()
        } else {
            for (child in statements) {
                processExpression(child)
                if (child != statements.last()) {
                    addInstruction(PopInstruction())
                }
                if (broken) return
            }
        }
    }

    private fun processDeclaration(variable: KtProperty) {
        val initializer = variable.initializer
        if (initializer == null) {
            pushUnknown()
            return
        }
        val dfaVariable = factory.varFactory.createVariableValue(KtLocalVariableDescriptor(variable))
        processExpression(initializer)
        addImplicitConversion(initializer, variable.type())
        addInstruction(SimpleAssignmentInstruction(KotlinExpressionAnchor(variable), dfaVariable))
    }

    private fun processReturnExpression(expr: KtReturnExpression) {
        if (expr.labeledExpression != null) {
            // TODO: support labels
            broken = true
            return
        }
        processExpression(expr.returnedExpression)
        addInstruction(ReturnInstruction(factory, traps, expr))
    }

    private fun processReferenceExpression(expr: KtSimpleNameExpression) {
        val descriptor = KtLocalVariableDescriptor.create(expr)
        if (descriptor != null) {
            addInstruction(JvmPushInstruction(descriptor.createValue(factory, null), KotlinExpressionAnchor(expr)))
            return
        }
        // TODO: support other references
        broken = true
    }

    private fun processConstantExpression(expr: KtConstantExpression) {
        addInstruction(PushValueInstruction(getConstant(expr), KotlinExpressionAnchor(expr)))
    }

    private fun pushUnknown() {
        addInstruction(PushValueInstruction(DfType.TOP))
    }

    private fun processBinaryExpression(expr: KtBinaryExpression) {
        val token = expr.operationToken
        val relation = relationFromToken(token)
        if (relation != null) {
            processBinaryRelationExpression(expr, relation, token == KtTokens.EXCLEQ || token == KtTokens.EQEQ)
            return
        }
        val leftType = expr.left?.getKotlinType()?.toDfType() ?: DfType.TOP
        if (leftType is DfIntegralType) {
            val mathOp = mathOpFromToken(expr.operationReference)
            if (mathOp != null) {
                processMathExpression(expr, mathOp)
                return
            }
        }
        if (token === KtTokens.ANDAND || token === KtTokens.OROR) {
            processShortCircuitExpression(expr, token === KtTokens.ANDAND)
            return
        }
        if (ASSIGNMENT_TOKENS.contains(token)) {
            processAssignmentExpression(expr)
            return
        }
        if (token === KtTokens.ELVIS) {
            processNullSafeOperator(expr)
            return
        }
        // TODO: support other operators
        processExpression(expr.left)
        processExpression(expr.right)
        addInstruction(EvalUnknownInstruction(KotlinExpressionAnchor(expr), 2))
        addInstruction(FlushFieldsInstruction())
    }

    private fun processNullSafeOperator(expr: KtBinaryExpression) {
        processExpression(expr.left)
        addInstruction(DupInstruction())
        val offset = DeferredOffset()
        addInstruction(ConditionalGotoInstruction(offset, DfTypes.NULL))
        val endOffset = DeferredOffset()
        addInstruction(GotoInstruction(endOffset))
        setOffset(offset)
        addInstruction(PopInstruction())
        processExpression(expr.right)
        setOffset(endOffset)
    }

    private fun processAssignmentExpression(expr: KtBinaryExpression) {
        val left = expr.left
        val right = expr.right
        val descriptor = KtLocalVariableDescriptor.create(left)
        val leftType = left?.getKotlinType()
        val rightType = right?.getKotlinType()
        if (descriptor == null) {
            processExpression(left)
            addInstruction(PopInstruction())
            processExpression(right)
            addImplicitConversion(right, leftType)
            // TODO: support qualified assignments
            addInstruction(FlushFieldsInstruction())
            return
        }
        val token = expr.operationToken
        val mathOp = mathOpFromAssignmentToken(token)
        if (mathOp != null) {
            val resultType = balanceType(leftType, rightType)
            processExpression(left)
            addImplicitConversion(left, resultType)
            processExpression(right)
            addImplicitConversion(right, resultType)
            addInstruction(NumericBinaryInstruction(mathOp, KotlinExpressionAnchor(expr)))
            addImplicitConversion(right, resultType, leftType)
        } else {
            processExpression(right)
            addImplicitConversion(right, leftType)
        }
        // TODO: support overloaded assignment
        addInstruction(SimpleAssignmentInstruction(KotlinExpressionAnchor(expr), factory.varFactory.createVariableValue(descriptor)))
        addInstruction(FinishElementInstruction(expr))
    }

    private fun processShortCircuitExpression(expr: KtBinaryExpression, and: Boolean) {
        val left = expr.left
        val right = expr.right
        val endOffset = DeferredOffset()
        processExpression(left)
        val nextOffset = DeferredOffset()
        addInstruction(ConditionalGotoInstruction(nextOffset, DfTypes.booleanValue(and), left))
        val anchor = KotlinExpressionAnchor(expr)
        addInstruction(PushValueInstruction(DfTypes.booleanValue(!and), anchor))
        addInstruction(GotoInstruction(endOffset))
        setOffset(nextOffset)
        addInstruction(FinishElementInstruction(null))
        processExpression(right)
        setOffset(endOffset)
        addInstruction(ResultOfInstruction(anchor))
    }

    private fun processMathExpression(expr: KtBinaryExpression, mathOp: LongRangeBinOp) {
        val left = expr.left
        val right = expr.right
        val resultType = expr.getKotlinType()
        processExpression(left)
        addImplicitConversion(left, resultType)
        processExpression(right)
        if (!mathOp.isShift) {
            addImplicitConversion(right, resultType)
        }
        addInstruction(NumericBinaryInstruction(mathOp, KotlinExpressionAnchor(expr)))
    }

    private fun addImplicitConversion(expression: KtExpression?, expectedType: KotlinType?) {
        addImplicitConversion(expression, expression?.getKotlinType(), expectedType)
    }

    private fun addImplicitConversion(expression: KtExpression?, actualType: KotlinType?, expectedType: KotlinType?) {
        expression ?: return
        actualType ?: return
        expectedType ?: return
        if (actualType == expectedType) return
        val actualPsiType = actualType.toPsiType(expression)
        val expectedPsiType = expectedType.toPsiType(expression)
        if (actualPsiType is PsiPrimitiveType && expectedPsiType is PsiPrimitiveType) {
            addInstruction(PrimitiveConversionInstruction(expectedPsiType, null))
        }
    }

    private fun processBinaryRelationExpression(
        expr: KtBinaryExpression, relation: RelationType,
        forceEqualityByContent: Boolean
    ) {
        val left = expr.left
        val right = expr.right
        val balancedType = balanceType(left?.getKotlinType(), right?.getKotlinType())
        processExpression(left)
        addImplicitConversion(left, balancedType)
        processExpression(right)
        addImplicitConversion(right, balancedType)
        if (left == null || right == null) {
            addInstruction(EvalUnknownInstruction(KotlinExpressionAnchor(expr), 2))
            return
        }
        // TODO: support overloaded operators
        // TODO: avoid equals-comparison of unknown object types
        addInstruction(BooleanBinaryInstruction(relation, forceEqualityByContent, KotlinExpressionAnchor(expr)))
    }

    private fun balanceType(left: KotlinType?, right: KotlinType?): KotlinType? {
        if (left == null || right == null) return null
        if (left.isDouble()) return left
        if (right.isDouble()) return right
        if (left.isFloat()) return left
        if (right.isFloat()) return right
        if (left.isLong()) return left
        if (right.isLong()) return right
        // null means no balancing is necessary
        return null
    }

    private fun addInstruction(inst: Instruction) {
        flow.addInstruction(inst)
    }

    private fun setOffset(offset: DeferredOffset) {
        offset.setOffset(flow.instructionCount)
    }

    private fun processIfExpression(ifExpression: KtIfExpression) {
        val condition = ifExpression.condition
        processExpression(condition)
        val skipThenOffset = DeferredOffset()
        val thenStatement = ifExpression.then
        val elseStatement = ifExpression.`else`
        addInstruction(ConditionalGotoInstruction(skipThenOffset, DfTypes.FALSE, condition))
        addInstruction(FinishElementInstruction(null))
        processExpression(thenStatement)

        val skipElseOffset = DeferredOffset()
        val instruction: Instruction = GotoInstruction(skipElseOffset)
        addInstruction(instruction)
        setOffset(skipThenOffset)
        addInstruction(FinishElementInstruction(null))
        processExpression(elseStatement)
        setOffset(skipElseOffset)
        addInstruction(FinishElementInstruction(ifExpression))
    }
    
    companion object {
        val ASSIGNMENT_TOKENS = TokenSet.create(KtTokens.EQ, KtTokens.PLUSEQ, KtTokens.MINUSEQ, KtTokens.MULTEQ, KtTokens.DIVEQ, KtTokens.PERCEQ)
    }
}
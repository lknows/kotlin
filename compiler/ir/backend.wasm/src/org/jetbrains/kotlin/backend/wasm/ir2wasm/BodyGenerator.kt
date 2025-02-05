/*
 * Copyright 2010-2020 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

@file:OptIn(ExperimentalUnsignedTypes::class)

package org.jetbrains.kotlin.backend.wasm.ir2wasm

import org.jetbrains.kotlin.backend.common.ir.returnType
import org.jetbrains.kotlin.backend.wasm.WasmBackendContext
import org.jetbrains.kotlin.backend.wasm.WasmSymbols
import org.jetbrains.kotlin.backend.wasm.utils.*
import org.jetbrains.kotlin.backend.wasm.utils.isCanonical
import org.jetbrains.kotlin.ir.IrBuiltIns
import org.jetbrains.kotlin.ir.IrElement
import org.jetbrains.kotlin.ir.backend.js.utils.erasedUpperBound
import org.jetbrains.kotlin.ir.backend.js.utils.findUnitGetInstanceFunction
import org.jetbrains.kotlin.ir.backend.js.utils.isDispatchReceiver
import org.jetbrains.kotlin.ir.backend.js.utils.realOverrideTarget
import org.jetbrains.kotlin.ir.declarations.*
import org.jetbrains.kotlin.ir.expressions.*
import org.jetbrains.kotlin.ir.symbols.IrClassSymbol
import org.jetbrains.kotlin.ir.types.*
import org.jetbrains.kotlin.ir.util.*
import org.jetbrains.kotlin.ir.visitors.IrElementVisitorVoid
import org.jetbrains.kotlin.ir.visitors.acceptVoid
import org.jetbrains.kotlin.js.config.JSConfigurationKeys
import org.jetbrains.kotlin.wasm.ir.*

class BodyGenerator(
    val context: WasmFunctionCodegenContext,
    private val hierarchyDisjointUnions: DisjointUnions<IrClassSymbol>
) : IrElementVisitorVoid {
    val body: WasmExpressionBuilder = context.bodyGen

    // Shortcuts
    private val backendContext: WasmBackendContext = context.backendContext
    private val wasmSymbols: WasmSymbols = backendContext.wasmSymbols
    private val irBuiltIns: IrBuiltIns = backendContext.irBuiltIns

    private val unitGetInstance by lazy { backendContext.findUnitGetInstanceFunction() }
    fun WasmExpressionBuilder.buildGetUnit() {
        buildInstr(WasmOp.GET_UNIT, WasmImmediate.FuncIdx(context.referenceFunction(unitGetInstance.symbol)))
    }

    // Generates code for the given IR element. Leaves something on the stack unless expression was of the type Void.
    private fun generateExpression(elem: IrElement) {
        assert(elem is IrExpression || elem is IrVariable) { "Unsupported statement kind" }

        elem.acceptVoid(this)

        if (elem is IrExpression && elem.type == irBuiltIns.nothingType) {
            body.buildUnreachable()
        }
    }

    // Generates code for the given IR element but *never* leaves anything on the stack.
    private fun generateStatement(statement: IrElement) {
        assert(statement is IrExpression || statement is IrVariable) { "Unsupported statement kind" }

        generateExpression(statement)
        if (statement is IrExpression && statement.type != wasmSymbols.voidType) {
            body.buildDrop()
        }
    }

    override fun visitElement(element: IrElement) {
        error("Unexpected element of type ${element::class}")
    }

    override fun visitThrow(expression: IrThrow) {
        generateExpression(expression.value)
        body.buildThrow(context.tagIdx)
    }

    override fun visitTry(aTry: IrTry) {
        assert(aTry.isCanonical(irBuiltIns)) { "expected canonical try/catch" }

        val resultType = context.transformBlockResultType(aTry.type)
        body.buildTry(null, resultType)
        generateExpression(aTry.tryResult)

        body.buildCatch(context.tagIdx)

        // Exception object is on top of the stack, store it into the local
        aTry.catches.single().catchParameter.symbol.let {
            context.defineLocal(it)
            body.buildSetLocal(context.referenceLocal(it))
        }
        generateExpression(aTry.catches.single().result)

        body.buildEnd()
    }

    override fun visitTypeOperator(expression: IrTypeOperatorCall) {
        when (expression.operator) {
            IrTypeOperator.REINTERPRET_CAST -> generateExpression(expression.argument)
            IrTypeOperator.IMPLICIT_COERCION_TO_UNIT -> {
                generateStatement(expression.argument)
                body.buildGetUnit()
            }
            else -> assert(false) { "Other types of casts must be lowered" }
        }
    }

    override fun visitConst(expression: IrConst<*>): Unit =
        generateConstExpression(expression, body, context)

    override fun visitGetField(expression: IrGetField) {
        val field: IrField = expression.symbol.owner
        val receiver: IrExpression? = expression.receiver
        if (receiver != null) {
            generateExpression(receiver)
            if (backendContext.inlineClassesUtils.isClassInlineLike(field.parentAsClass)) {
                // Unboxed inline class instance is already represented as backing field.
                // Doing nothing.
            } else {
                generateInstanceFieldAccess(field)
            }
        } else {
            body.buildGetGlobal(context.referenceGlobalField(field.symbol))
        }
    }

    private fun generateInstanceFieldAccess(field: IrField) {
        val opcode = when (field.type) {
            irBuiltIns.charType ->
                WasmOp.STRUCT_GET_U

            irBuiltIns.booleanType,
            irBuiltIns.byteType,
            irBuiltIns.shortType ->
                WasmOp.STRUCT_GET_S

            else -> WasmOp.STRUCT_GET
        }

        body.buildInstr(
            opcode,
            WasmImmediate.GcType(context.referenceGcType(field.parentAsClass.symbol)),
            WasmImmediate.StructFieldIdx(context.getStructFieldRef(field))
        )
    }

    override fun visitSetField(expression: IrSetField) {
        val field = expression.symbol.owner
        val receiver = expression.receiver

        if (receiver != null) {
            generateExpression(receiver)
            generateExpression(expression.value)
            body.buildStructSet(
                struct = context.referenceGcType(field.parentAsClass.symbol),
                fieldId = context.getStructFieldRef(field),
            )
        } else {
            generateExpression(expression.value)
            body.buildSetGlobal(context.referenceGlobalField(expression.symbol))
        }

        body.buildGetUnit()
    }

    override fun visitGetValue(expression: IrGetValue) {
        val valueSymbol = expression.symbol
        val valueDeclaration = valueSymbol.owner
        body.buildGetLocal(
            // Handle cases when IrClass::thisReceiver is referenced instead
            // of the value parameter of current function
            if (valueDeclaration.isDispatchReceiver)
                context.referenceLocal(0)
            else
                context.referenceLocal(valueSymbol)
        )
    }

    override fun visitSetValue(expression: IrSetValue) {
        generateExpression(expression.value)
        body.buildSetLocal(context.referenceLocal(expression.symbol))
        body.buildGetUnit()
    }

    override fun visitCall(expression: IrCall) {
        generateCall(expression)
    }

    override fun visitConstructorCall(expression: IrConstructorCall) {
        val klass: IrClass = expression.symbol.owner.parentAsClass

        require(!backendContext.inlineClassesUtils.isClassInlineLike(klass)) {
            "All inline class constructor calls must be lowered to static function calls"
        }

        val wasmGcType: WasmSymbol<WasmTypeDeclaration> = context.referenceGcType(klass.symbol)

        if (klass.getWasmArrayAnnotation() != null) {
            require(expression.valueArgumentsCount == 1) { "@WasmArrayOf constructs must have exactly one argument" }
            generateExpression(expression.getValueArgument(0)!!)
            body.buildInstr(
                WasmOp.ARRAY_NEW_DEFAULT,
                WasmImmediate.GcType(wasmGcType)
            )
            return
        }

        //ClassITable and VTable load
        body.buildGetGlobal(context.referenceGlobalVTable(klass.symbol))
        if (klass.hasInterfaceSuperClass()) {
            body.buildGetGlobal(context.referenceGlobalClassITable(klass.symbol))
        } else {
            body.buildRefNull(WasmHeapType.Simple.Data)
        }

        val wasmClassId = context.referenceClassId(klass.symbol)

        val irFields: List<IrField> = klass.allFields(backendContext.irBuiltIns)

        irFields.forEachIndexed { index, field ->
            if (index == 0)
                body.buildConstI32Symbol(wasmClassId)
            else
                generateDefaultInitializerForType(context.transformType(field.type), body)
        }

        body.buildStructNew(wasmGcType)
        generateCall(expression)
    }

    override fun visitDelegatingConstructorCall(expression: IrDelegatingConstructorCall) {
        val klass = context.irFunction.parentAsClass

        // Don't delegate constructors of Any to Any.
        if (klass.defaultType.isAny()) {
            body.buildGetUnit()
            return
        }

        body.buildGetLocal(context.referenceLocal(0))  // this parameter
        generateCall(expression)
    }

    private fun generateCall(call: IrFunctionAccessExpression) {
        // Box intrinsic has an additional klass ID argument.
        // Processing it separately
        if (call.symbol == wasmSymbols.boxIntrinsic) {
            val klass = call.getTypeArgument(0)!!.getRuntimeClass(irBuiltIns)
            val klassSymbol = klass.symbol

            //ClassITable and VTable load
            body.buildGetGlobal(context.referenceGlobalVTable(klassSymbol))
            if (klass.hasInterfaceSuperClass()) {
                body.buildGetGlobal(context.referenceGlobalClassITable(klassSymbol))
            } else {
                body.buildRefNull(WasmHeapType.Simple.Data)
            }

            body.buildConstI32Symbol(context.referenceClassId(klassSymbol))
            body.buildConstI32(0) // Any::_hashCode
            generateExpression(call.getValueArgument(0)!!)
            body.buildStructNew(context.referenceGcType(klassSymbol))
            return
        }

        // Get unit is a special case because it is the only function which returns the real unit instance.
        if (call.symbol == unitGetInstance.symbol) {
            body.buildGetUnit()
            return
        }

        // Some intrinsics are a special case because we want to remove them completely, including their arguments.
        val removableIntrinsics = buildList {
            if (backendContext.configuration.getNotNull(JSConfigurationKeys.WASM_ENABLE_ARRAY_RANGE_CHECKS) == false)
                add(wasmSymbols.rangeCheck)
            if (backendContext.configuration.getNotNull(JSConfigurationKeys.WASM_ENABLE_ASSERTS) == false)
                addAll(wasmSymbols.assertFuncs)
        }

        if (call.symbol in removableIntrinsics) {
            body.buildGetUnit()
            return
        }

        val function: IrFunction = call.symbol.owner.realOverrideTarget

        call.dispatchReceiver?.let { generateExpression(it) }
        call.extensionReceiver?.let { generateExpression(it) }
        for (i in 0 until call.valueArgumentsCount) {
            val valueArgument = call.getValueArgument(i)
            if (valueArgument == null) {
                generateDefaultInitializerForType(context.transformType(function.valueParameters[i].type), body)
            } else {
                generateExpression(valueArgument)
            }
        }

        if (tryToGenerateIntrinsicCall(call, function)) {
            if (function.returnType == irBuiltIns.unitType)
                body.buildGetUnit()
            return
        }

        val isSuperCall = call is IrCall && call.superQualifierSymbol != null
        if (function is IrSimpleFunction && function.isOverridable && !isSuperCall) {
            // Generating index for indirect call
            val klass = function.parentAsClass
            if (!klass.isInterface) {
                val classMetadata = context.getClassMetadata(klass.symbol)
                val vfSlot = classMetadata.virtualMethods.indexOfFirst { it.function == function }
                // Dispatch receiver should be simple and without side effects at this point
                // TODO: Verify
                val receiver = call.dispatchReceiver!!
                generateExpression(receiver)

                //TODO: check why it could be needed
                generateRefCast(receiver.type, klass.defaultType)

                body.buildStructGet(context.referenceGcType(klass.symbol), WasmSymbol(0))
                body.buildStructGet(context.referenceVTableGcType(klass.symbol), WasmSymbol(vfSlot))
                body.buildInstr(WasmOp.CALL_REF)
            } else {
                val symbol = klass.symbol
                if (symbol in hierarchyDisjointUnions) {
                    generateExpression(call.dispatchReceiver!!)

                    body.buildStructGet(context.referenceGcType(irBuiltIns.anyClass), WasmSymbol(1))

                    val classITableReference = context.referenceClassITableGcType(symbol)
                    body.buildRefCastStatic(classITableReference)
                    body.buildStructGet(classITableReference, context.referenceClassITableInterfaceSlot(symbol))

                    val vfSlot = context.getInterfaceMetadata(symbol).methods
                        .indexOfFirst { it.function == function }

                    body.buildStructGet(context.referenceVTableGcType(symbol), WasmSymbol(vfSlot))
                    body.buildInstr(WasmOp.CALL_REF)
                } else {
                    body.buildUnreachable()
                }
            }

        } else {
            // Static function call
            body.buildCall(context.referenceFunction(function.symbol))
        }

        // Unit types don't cross function boundaries
        if (function.returnType == irBuiltIns.unitType)
            body.buildGetUnit()
    }

    private fun generateRefCast(fromType: IrType, toType: IrType) {
        if (!isDownCastAlwaysSuccessInRuntime(fromType, toType)) {
            body.buildRefCastStatic(
                toType = context.referenceGcType(toType.getRuntimeClass(irBuiltIns).symbol)
            )
        }
    }

    private fun generateRefTest(fromType: IrType, toType: IrType) {
        if (!isDownCastAlwaysSuccessInRuntime(fromType, toType)) {
            body.buildRefTestStatic(
                toType = context.referenceGcType(toType.getRuntimeClass(irBuiltIns).symbol)
            )
        } else {
            body.buildDrop()
            body.buildConstI32(1)
        }
    }

    private fun isDownCastAlwaysSuccessInRuntime(fromType: IrType, toType: IrType): Boolean {
        val upperBound = fromType.erasedUpperBound
        if (upperBound != null && upperBound.symbol.isSubtypeOfClass(backendContext.wasmSymbols.wasmAnyRefClass)) {
            return false
        }
        return fromType.getRuntimeClass(irBuiltIns).isSubclassOf(toType.getRuntimeClass(irBuiltIns))
    }

    // Return true if generated.
    // Assumes call arguments are already on the stack
    private fun tryToGenerateIntrinsicCall(
        call: IrFunctionAccessExpression,
        function: IrFunction
    ): Boolean {
        if (tryToGenerateWasmOpIntrinsicCall(function)) {
            return true
        }

        when (function.symbol) {
            wasmSymbols.wasmClassId -> {
                val klass = call.getTypeArgument(0)!!.getClass()
                    ?: error("No class given for wasmClassId intrinsic")
                assert(!klass.isInterface)
                body.buildConstI32Symbol(context.referenceClassId(klass.symbol))
            }

            wasmSymbols.wasmInterfaceId -> {
                val irInterface = call.getTypeArgument(0)!!.getClass()
                    ?: error("No interface given for wasmInterfaceId intrinsic")
                assert(irInterface.isInterface)
                body.buildConstI32Symbol(context.referenceInterfaceId(irInterface.symbol))
            }

            wasmSymbols.wasmIsInterface -> {
                val irInterface = call.getTypeArgument(0)!!.getClass()
                    ?: error("No interface given for wasmInterfaceId intrinsic")
                assert(irInterface.isInterface)
                if (irInterface.symbol in hierarchyDisjointUnions) {
                    val classITable = context.referenceClassITableGcType(irInterface.symbol)
                    val parameterLocal = context.referenceLocal(SyntheticLocalType.IS_INTERFACE_PARAMETER)
                    body.buildSetLocal(parameterLocal)
                    body.buildBlock("isInterface", WasmI32) { outerLabel ->
                        body.buildBlock("isInterface", WasmRefNullType(WasmHeapType.Simple.Data)) { innerLabel ->
                            body.buildGetLocal(parameterLocal)
                            body.buildStructGet(context.referenceGcType(irBuiltIns.anyClass), WasmSymbol(1))
                            body.buildBrInstr(WasmOp.BR_ON_CAST_STATIC_FAIL, innerLabel, classITable)
                            body.buildStructGet(classITable, context.referenceClassITableInterfaceSlot(irInterface.symbol))
                            body.buildInstr(WasmOp.REF_IS_NULL)
                            body.buildInstr(WasmOp.I32_EQZ)
                            body.buildBr(outerLabel)
                        }
                        body.buildDrop()
                        body.buildConstI32(0)
                    }
                } else {
                    body.buildDrop()
                    body.buildConstI32(0)
                }
            }

            wasmSymbols.refCast -> {
                generateRefCast(
                    fromType = call.getValueArgument(0)!!.type,
                    toType = call.getTypeArgument(0)!!
                )
            }

            wasmSymbols.refTest -> {
                generateRefTest(
                    fromType = call.getValueArgument(0)!!.type,
                    toType = call.getTypeArgument(0)!!
                )
            }

            wasmSymbols.unboxIntrinsic -> {
                val fromType = call.getTypeArgument(0)!!

                if (fromType.isNothing()) {
                    body.buildUnreachable()
                    // TODO: Investigate why?
                    return true
                }

                // Workaround test codegen/box/elvis/nullNullOk.kt
                if (fromType.makeNotNull().isNothing()) {
                    body.buildUnreachable()
                    return true
                }

                val toType = call.getTypeArgument(1)!!
                val klass: IrClass = backendContext.inlineClassesUtils.getInlinedClass(toType)!!
                val field = getInlineClassBackingField(klass)

                generateRefCast(fromType, toType)
                generateInstanceFieldAccess(field)
            }

            wasmSymbols.unsafeGetScratchRawMemory -> {
                // TODO: This drops size of the allocated segment. Instead we can check that it's in bounds for better error messages.
                body.buildDrop()
                body.buildConstI32Symbol(context.scratchMemAddr)
            }

            wasmSymbols.unsafeGetScratchRawMemorySize -> {
                body.buildConstI32Symbol(WasmSymbol(context.scratchMemSizeInBytes))
            }

            wasmSymbols.wasmArrayCopy -> {
                val immediate = WasmImmediate.GcType(
                    context.referenceGcType(call.getTypeArgument(0)!!.getRuntimeClass(irBuiltIns).symbol)
                )
                body.buildInstr(WasmOp.ARRAY_COPY, immediate, immediate)
            }

            else -> {
                return false
            }
        }
        return true
    }

    override fun visitBlockBody(body: IrBlockBody) {
        body.statements.forEach(::generateStatement)
    }

    override fun visitContainerExpression(expression: IrContainerExpression) {
        val statements = expression.statements

        if (statements.isEmpty()) {
            if (expression.type == irBuiltIns.unitType)
                body.buildGetUnit()
            return
        }

        statements.dropLast(1).forEach {
            generateStatement(it)
        }

        generateExpression(statements.last())

        // This handles cases where the last statement of a block is declaration which doesn't produce any value,
        // but the block itself marked with the unit type.
        if (statements.last() !is IrExpression && expression.type != wasmSymbols.voidType) {
            body.buildGetUnit()
        }
    }

    override fun visitBreak(jump: IrBreak) {
        assert(jump.type == irBuiltIns.nothingType)
        body.buildBr(context.referenceLoopLevel(jump.loop, LoopLabelType.BREAK))
    }

    override fun visitContinue(jump: IrContinue) {
        assert(jump.type == irBuiltIns.nothingType)
        body.buildBr(context.referenceLoopLevel(jump.loop, LoopLabelType.CONTINUE))
    }

    override fun visitReturn(expression: IrReturn) {
        if (expression.returnTargetSymbol.owner.returnType(backendContext) == irBuiltIns.unitType &&
            expression.returnTargetSymbol.owner != unitGetInstance
        ) {
            generateStatement(expression.value)
        } else {
            generateExpression(expression.value)
        }

        if (context.irFunction is IrConstructor) {
            body.buildGetLocal(context.referenceLocal(0))
        }

        body.buildInstr(WasmOp.RETURN)
    }

    override fun visitWhen(expression: IrWhen) {
        val resultType = context.transformBlockResultType(expression.type)
        var ifCount = 0
        var seenElse = false

        for (branch in expression.branches) {
            if (!isElseBranch(branch)) {
                generateExpression(branch.condition)
                body.buildIf(null, resultType)
                generateExpression(branch.result)
                if (expression.type == irBuiltIns.nothingType) {
                    body.buildUnreachable()
                }
                body.buildElse()
                ifCount++
            } else {
                generateExpression(branch.result)
                if (expression.type == irBuiltIns.nothingType) {
                    body.buildUnreachable()
                }
                seenElse = true
                break
            }
        }

        // Always generate the last else to make verifier happy. If this when expression is exhaustive we will never reach the last else.
        // If it's not exhaustive it must be used as a statement (per kotlin spec) and so the result value of the last else will never be used.
        if (!seenElse && resultType != null) {
            assert(expression.type != irBuiltIns.nothingType)
            generateDefaultInitializerForType(resultType, body)
        }

        repeat(ifCount) { body.buildEnd() }
    }

    override fun visitDoWhileLoop(loop: IrDoWhileLoop) {
        // (loop $LABEL
        //     (block $BREAK_LABEL
        //         (block $CONTINUE_LABEL <LOOP BODY>)
        //         (br_if $LABEL          <CONDITION>)))

        val label = loop.label

        body.buildLoop(label) { wasmLoop ->
            body.buildBlock("BREAK_$label") { wasmBreakBlock ->
                body.buildBlock("CONTINUE_$label") { wasmContinueBlock ->
                    context.defineLoopLevel(loop, LoopLabelType.BREAK, wasmBreakBlock)
                    context.defineLoopLevel(loop, LoopLabelType.CONTINUE, wasmContinueBlock)
                    loop.body?.let { generateStatement(it) }
                }
                generateExpression(loop.condition)
                body.buildBrIf(wasmLoop)
            }
        }

        body.buildGetUnit()
    }

    override fun visitWhileLoop(loop: IrWhileLoop) {
        // (loop $CONTINUE_LABEL
        //     (block $BREAK_LABEL
        //         (br_if $BREAK_LABEL (i32.eqz <CONDITION>))
        //         <LOOP_BODY>
        //         (br $CONTINUE_LABEL)))

        val label = loop.label

        body.buildLoop(label) { wasmLoop ->
            body.buildBlock("BREAK_$label") { wasmBreakBlock ->
                context.defineLoopLevel(loop, LoopLabelType.BREAK, wasmBreakBlock)
                context.defineLoopLevel(loop, LoopLabelType.CONTINUE, wasmLoop)

                generateExpression(loop.condition)
                body.buildInstr(WasmOp.I32_EQZ)
                body.buildBrIf(wasmBreakBlock)
                loop.body?.let {
                    generateStatement(it)
                }
                body.buildBr(wasmLoop)
            }
        }

        body.buildGetUnit()
    }

    override fun visitVariable(declaration: IrVariable) {
        context.defineLocal(declaration.symbol)
        if (declaration.initializer == null) {
            return
        }
        val init = declaration.initializer!!
        generateExpression(init)
        val varName = context.referenceLocal(declaration.symbol)
        body.buildSetLocal(varName)
    }

    // Return true if function is recognized as intrinsic.
    private fun tryToGenerateWasmOpIntrinsicCall(function: IrFunction): Boolean {
        if (function.hasWasmNoOpCastAnnotation()) {
            return true
        }

        val opString = function.getWasmOpAnnotation()
        if (opString != null) {
            val op = WasmOp.valueOf(opString)
            when (op.immediates.size) {
                0 -> {
                    body.buildInstr(op)
                }
                1 -> {
                    val immediates = arrayOf(
                        when (val imm = op.immediates[0]) {
                            WasmImmediateKind.MEM_ARG ->
                                WasmImmediate.MemArg(0u, 0u)
                            WasmImmediateKind.STRUCT_TYPE_IDX ->
                                WasmImmediate.GcType(context.referenceGcType(function.dispatchReceiverParameter!!.type.classOrNull!!))
                            WasmImmediateKind.TYPE_IDX ->
                                WasmImmediate.TypeIdx(context.referenceGcType(function.dispatchReceiverParameter!!.type.classOrNull!!))

                            else ->
                                error("Immediate $imm is unsupported")
                        }
                    )
                    body.buildInstr(op, *immediates)
                }
                else ->
                    error("Op $opString is unsupported")
            }
            return true
        }

        return false
    }
}


package com.pixelmc.pixelmcmanager.api;

import static com.pixelmc.pixelmcmanager.api.GameplayData.row;

import static org.objectweb.asm.Opcodes.*;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;

import java.util.Arrays;
import java.util.List;

/** A deliberately narrow, executable proof for the audited Composite Material spawner branch. */
final class CompositeEventProof {
    static final String OUTPUT = "minecraft:spawner";
    static final String TOOL = "composite_material:dungeon_pickaxe";
    static final String ROOT = "io/github/rcneg/compositematerial/";
    static final String TOOL_REGISTRY = ROOT + "common/init/ItemRegistry";
    static final String TOOL_FIELD = "DUNGEON_PICKAXE";
    private static final String HANDLER = ROOT + "common/events/InteractEvents";
    private static final String EVENT = "net/minecraftforge/event/level/BlockEvent$BreakEvent";
    private static final String STACK = "net/minecraft/world/item/ItemStack";
    private static final String BLOCKS = "net/minecraft/world/level/block/Blocks";
    private static final String ITEMS = "net/minecraft/world/item/Items";
    private static final String ENTITY = "net/minecraft/world/entity/item/ItemEntity";
    private static final String WORLD = "net/minecraft/world/level/Level";
    private static final String STATE = "net/minecraft/world/level/block/state/BlockState";

    static boolean matches(ClassNode type, MethodNode method) {
        if (!type.name.equals(HANDLER)
                || !method.name.equals("onBlockBreak")
                || !method.desc.equals("(L" + EVENT + ";)V")
                || (method.access & ACC_STATIC) == 0
                || !method.tryCatchBlocks.isEmpty()
                || type.visibleAnnotations == null
                || type.visibleAnnotations.stream()
                        .noneMatch(
                                annotation ->
                                        annotation.desc.equals(
                                                        "Lnet/minecraftforge/fml/common/Mod$EventBusSubscriber;")
                                                && annotation.values == null)
                || method.visibleAnnotations == null
                || method.visibleAnnotations.stream()
                        .noneMatch(
                                annotation ->
                                        annotation.desc.equals(
                                                        "Lnet/minecraftforge/eventbus/api/SubscribeEvent;")
                                                && annotation.values == null)) return false;
        var ops =
                Arrays.stream(method.instructions.toArray())
                        .filter(op -> op.getOpcode() >= 0)
                        .toList();
        if (ops.size() < 100 || !initialLocals(ops)) return false;
        int tool = fieldIndex(ops, TOOL_REGISTRY, TOOL_FIELD);
        int block = fieldIndex(ops, BLOCKS, "SPAWNER", "f_50085_");
        int output = fieldIndex(ops, ITEMS, "SPAWNER", "f_42007_");
        if (tool < 15 || tool >= block || block >= output || output + 16 >= ops.size())
            return false;
        if (!heldAndBlock(ops, tool, block)) return false;

        // No handler branch may replace the event position, held stack, level or broken state used
        // here.
        for (int i = 14; i < ops.size(); i++) {
            if (ops.get(i) instanceof VarInsnNode var
                    && var.var <= 4
                    && var.getOpcode() >= ISTORE
                    && var.getOpcode() <= ASTORE) return false;
        }
        if (ops.subList(tool, ops.size()).stream().filter(op -> op instanceof JumpInsnNode).count()
                != 3) return false;
        return exactSpawn(ops, block + 3, output);
    }

    private static boolean initialLocals(List<AbstractInsnNode> ops) {
        return local(ops.get(0), ALOAD, 0)
                && call(ops.get(1), EVENT, "getPos")
                && local(ops.get(2), ASTORE, 1)
                && local(ops.get(3), ALOAD, 0)
                && call(ops.get(4), EVENT, "getPlayer")
                && call(
                        ops.get(5),
                        "net/minecraft/world/entity/player/Player",
                        "getMainHandItem",
                        "m_21205_")
                && local(ops.get(6), ASTORE, 2)
                && local(ops.get(7), ALOAD, 0)
                && call(ops.get(8), EVENT, "getPlayer")
                && call(ops.get(9), "net/minecraft/world/entity/player/Player", "level", "m_9236_")
                && local(ops.get(10), ASTORE, 3)
                && local(ops.get(11), ALOAD, 0)
                && call(ops.get(12), EVENT, "getState")
                && local(ops.get(13), ASTORE, 4);
    }

    private static boolean heldAndBlock(List<AbstractInsnNode> ops, int tool, int block) {
        if (!local(ops.get(tool - 1), ALOAD, 2)
                || !call(ops.get(tool + 1), "net/minecraftforge/registries/RegistryObject", "get")
                || !type(ops.get(tool + 2), CHECKCAST, "net/minecraft/world/item/Item")
                || !call(ops.get(tool + 3), STACK, "is", "m_150930_")
                || !local(ops.get(block - 1), ALOAD, 4)
                || !call(ops.get(block + 1), STATE, "is", "m_60713_")) return false;
        if (!(ops.get(tool + 4) instanceof JumpInsnNode wrongTool)
                || wrongTool.getOpcode() != IFEQ
                || !(ops.get(block + 2) instanceof JumpInsnNode wrongBlock)
                || wrongBlock.getOpcode() != IFEQ) return false;
        var end = ops.get(ops.size() - 1);
        if (end.getOpcode() != RETURN
                || realNext(wrongTool.label) != end
                || realNext(wrongBlock.label) != end) return false;
        var oreBranch =
                ops.subList(tool + 5, block - 1).stream()
                        .filter(op -> op instanceof JumpInsnNode)
                        .toList();
        return oreBranch.size() == 1
                && oreBranch.get(0) instanceof JumpInsnNode ore
                && ore.getOpcode() == IFEQ
                && realNext(ore.label) == ops.get(block - 1);
    }

    private static boolean exactSpawn(List<AbstractInsnNode> ops, int lane, int output) {
        var relevant = ops.subList(lane, ops.size());
        var calls =
                relevant.stream()
                        .filter(MethodInsnNode.class::isInstance)
                        .map(MethodInsnNode.class::cast)
                        .toList();
        // This lane has no alternate branch, target mutation, stack copy or NBT writer.
        if (calls.size() != 19 || relevant.stream().anyMatch(op -> op.getOpcode() == ATHROW))
            return false;
        var cancel = calls.get(4);
        var airState = calls.get(5);
        var setBlock = calls.get(6);
        int cancelIndex = ops.indexOf(cancel);
        int airIndex = ops.indexOf(airState);
        if (!call(cancel, EVENT, "setCanceled")
                || !local(ops.get(cancelIndex - 2), ALOAD, 0)
                || ops.get(cancelIndex - 1).getOpcode() != ICONST_1
                || !local(ops.get(airIndex - 3), ALOAD, 3)
                || !local(ops.get(airIndex - 2), ALOAD, 1)
                || !field(ops.get(airIndex - 1), BLOCKS, "AIR", "f_50016_")
                || !call(
                        airState,
                        "net/minecraft/world/level/block/Block",
                        "defaultBlockState",
                        "m_49966_")
                || ops.get(airIndex + 1).getOpcode() != ICONST_3
                || ops.get(airIndex + 2) != setBlock
                || !call(setBlock, WORLD, "setBlock", "m_7731_")) return false;
        if (!type(ops.get(output - 2), NEW, STACK)
                || ops.get(output - 1).getOpcode() != DUP
                || !constructor(
                        ops.get(output + 1), STACK, "(Lnet/minecraft/world/level/ItemLike;)V")
                || !constructor(ops.get(output + 2), ENTITY, "(L" + WORLD + ";DDDL" + STACK + ";)V")
                || !local(ops.get(output + 3), ASTORE, 5)
                || !local(ops.get(output + 7), ALOAD, 3)
                || !local(ops.get(output + 8), ALOAD, 5)
                || !call(ops.get(output + 9), WORLD, "addFreshEntity", "m_7967_")) return false;
        return ops.get(output + 10).getOpcode() == POP
                && local(ops.get(output + 11), ALOAD, 2)
                && ops.get(output + 12).getOpcode() == ICONST_1
                && local(ops.get(output + 13), ALOAD, 0)
                && call(ops.get(output + 14), EVENT, "getPlayer")
                && call(calls.get(18), STACK, "hurtAndBreak", "m_41622_");
    }

    private static AbstractInsnNode realNext(AbstractInsnNode op) {
        while (op != null && op.getOpcode() < 0) op = op.getNext();
        return op;
    }

    private static int fieldIndex(List<AbstractInsnNode> ops, String owner, String... names) {
        int found = -1;
        for (int i = 0; i < ops.size(); i++)
            if (field(ops.get(i), owner, names)) {
                if (found != -1) return -1;
                found = i;
            }
        return found;
    }

    private static boolean field(AbstractInsnNode insn, String owner, String... names) {
        return insn instanceof FieldInsnNode field
                && insn.getOpcode() == GETSTATIC
                && field.owner.equals(owner)
                && Arrays.asList(names).contains(field.name);
    }

    private static boolean call(AbstractInsnNode insn, String owner, String... names) {
        return insn instanceof MethodInsnNode call
                && call.owner.equals(owner)
                && Arrays.asList(names).contains(call.name);
    }

    private static boolean constructor(AbstractInsnNode insn, String owner, String desc) {
        return insn instanceof MethodInsnNode call
                && call.owner.equals(owner)
                && call.name.equals("<init>")
                && call.desc.equals(desc);
    }

    private static boolean local(AbstractInsnNode insn, int opcode, int number) {
        return insn instanceof VarInsnNode var && insn.getOpcode() == opcode && var.var == number;
    }

    private static boolean type(AbstractInsnNode insn, int opcode, String type) {
        return insn instanceof TypeInsnNode ref
                && insn.getOpcode() == opcode
                && ref.desc.equals(type);
    }

    static JsonObject fact(String handler) {
        var row = row("confidence", "verified");
        row.addProperty("sourceMod", "composite_material");
        row.addProperty("event", "BlockEvent$BreakEvent");
        row.addProperty("handler", handler);
        row.addProperty("action", "break_block");
        row.addProperty("role", "produced");
        row.addProperty("conditionsVerified", true);
        row.addProperty("tool", TOOL);
        row.addProperty("hand", "main_hand");
        row.addProperty("block", OUTPUT);
        row.addProperty("quantity", 1);
        var conditions = new JsonArray();
        conditions.add("主手持碎灵者（" + TOOL + "）");
        conditions.add("破坏原版刷怪笼，且破坏事件允许执行");
        row.add("conditions", conditions);
        var outputs = new JsonArray();
        var product = row("id", OUTPUT);
        product.addProperty("quantity", 1);
        product.addProperty("empty", true);
        outputs.add(product);
        row.add("outputs", outputs);
        row.addProperty(
                "fact", "在本服允许该次破坏事件执行时，主手持碎灵者破坏原版刷怪笼会掉落 1 个空刷怪笼（minecraft:spawner），不会保留原本的刷怪生物。");
        return row;
    }

    private CompositeEventProof() {}
}

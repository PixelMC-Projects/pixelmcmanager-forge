package com.pixelmc.pixelmcmanager.api;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.LookupSwitchInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TableSwitchInsnNode;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.AnalyzerException;
import org.objectweb.asm.tree.analysis.Frame;
import org.objectweb.asm.tree.analysis.SourceInterpreter;
import org.objectweb.asm.tree.analysis.SourceValue;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Bounded, conservative operand-flow hints. None of these hints alone proves an event outcome. */
final class EventOutputFlow {
    private static final String STACK = "net/minecraft/world/item/ItemStack";
    private static final String ITEM_ENTITY = "net/minecraft/world/entity/item/ItemEntity";
    private static final String RESOURCE = "net/minecraft/resources/ResourceLocation";
    private static final String ITEM_DESC = "Lnet/minecraft/world/item/Item;";
    private static final String STACK_DESC = "L" + STACK + ";";
    private static final String ENTITY_DESC = "Lnet/minecraft/world/entity/Entity;";
    private static final String ITEM_LIKE = "Lnet/minecraft/world/level/ItemLike;";

    static final int REFERENCED = 1;
    static final int CHECKED = 2;
    static final int CONSUMED = 4;
    static final int OUTPUT = 8;

    record Target(String id, String namespace, String path, Set<String> fields) {
        boolean reference(AbstractInsnNode node) {
            if (node instanceof LdcInsnNode ldc) return id.equals(ldc.cst);
            return node instanceof FieldInsnNode field
                    && node.getOpcode() == Opcodes.GETSTATIC
                    && fields.contains(field.owner + "." + field.name);
        }
    }

    private record InputCheck(SourceValue stack, MethodInsnNode call) {}

    static int roles(String owner, MethodNode method, Target target) throws AnalyzerException {
        var ops = method.instructions.toArray();
        boolean hasReference = false;
        for (var op : ops) {
            if (target.reference(op)) {
                hasReference = true;
                break;
            }
        }
        if (!hasReference && !splitId(ops, target)) return 0;
        if (ops.length > 2400) return REFERENCED;
        var frames = new Analyzer<>(new Origins()).analyze(owner, method);
        var matched = new HashSet<AbstractInsnNode>();
        var outputEntities = new HashSet<AbstractInsnNode>();
        var checks = new ArrayList<InputCheck>();
        int roles = REFERENCED;

        // Constructors write into a pre-existing NEW value; later calls may hold the surviving DUP.
        for (int index = 0; index < ops.length; index++) {
            var op = ops[index];
            if (target.reference(op)) matched.add(op);
            if (!(op instanceof MethodInsnNode call) || frames[index] == null) continue;
            var frame = frames[index];
            int args = Type.getArgumentTypes(call.desc).length;
            if (call.name.equals("<init>") && args > 0) {
                var instance = frame.getStack(frame.getStackSize() - args - 1);
                if (call.owner.equals(RESOURCE)
                        && args == 2
                        && literal(arg(frame, args, 0), target.namespace())
                        && literal(arg(frame, args, 1), target.path())) {
                    matched.addAll(instance.insns);
                } else if (call.owner.equals(RESOURCE) || call.owner.equals(STACK)) {
                    if (contains(matched, arg(frame, args, 0))) matched.addAll(instance.insns);
                } else if (call.owner.equals(ITEM_ENTITY)
                        && call.desc.endsWith(STACK_DESC + ")V")
                        && contains(matched, arg(frame, args, args - 1))) {
                    outputEntities.addAll(instance.insns);
                }
            }
            if (isItemCheck(call) && contains(matched, arg(frame, 1, 0))) {
                checks.add(new InputCheck(frame.getStack(frame.getStackSize() - 2), call));
                roles |= CHECKED;
            }
            if (directOutput(call)) {
                if (contains(matched, arg(frame, args, args - 1))) roles |= OUTPUT;
            } else if (call.desc.equals("(" + ENTITY_DESC + ")Z")
                    && name(call, "addFreshEntity", "m_7967_")
                    && contains(outputEntities, arg(frame, 1, 0))) {
                roles |= OUTPUT;
            }
            if (!checks.isEmpty()
                    && call.owner.equals(STACK)
                    && call.desc.equals("(I)V")
                    && name(call, "shrink", "m_41774_")
                    && guardedShrink(
                            method,
                            frames,
                            frame.getStack(frame.getStackSize() - 2),
                            checks,
                            index)) {
                roles |= CONSUMED;
            }
        }
        return roles;
    }

    private static boolean splitId(AbstractInsnNode[] ops, Target target) {
        boolean namespace = false;
        boolean path = false;
        for (var op : ops)
            if (op instanceof LdcInsnNode constant) {
                namespace |= target.namespace().equals(constant.cst);
                path |= target.path().equals(constant.cst);
            }
        return namespace && path;
    }

    private static boolean contains(Set<AbstractInsnNode> sources, SourceValue value) {
        return value.insns.stream().anyMatch(sources::contains);
    }

    private static boolean literal(SourceValue value, String text) {
        return value.insns.size() == 1
                && value.insns.iterator().next() instanceof LdcInsnNode ldc
                && text.equals(ldc.cst);
    }

    private static SourceValue arg(Frame<SourceValue> frame, int args, int index) {
        return frame.getStack(frame.getStackSize() - args + index);
    }

    private static boolean name(MethodInsnNode call, String mojang, String srg) {
        return call.name.equals(mojang) || call.name.equals(srg);
    }

    private static boolean isItemCheck(MethodInsnNode call) {
        return call.owner.equals(STACK)
                && call.desc.equals("(" + ITEM_DESC + ")Z")
                && name(call, "is", "m_150930_");
    }

    private static boolean directOutput(MethodInsnNode call) {
        if (call.owner.equals("net/minecraft/world/level/block/Block")) {
            return name(call, "popResource", "m_49840_") && call.desc.endsWith(STACK_DESC + ")V");
        }
        if (call.owner.equals("net/minecraft/world/entity/player/Inventory")) {
            return name(call, "add", "m_36054_") && call.desc.equals("(" + STACK_DESC + ")Z");
        }
        return call.owner.startsWith("net/minecraft/world/entity/")
                && (name(call, "spawnAtLocation", "m_19998_")
                        || name(call, "spawnAtLocation", "m_19983_"))
                && (call.desc.equals("(" + ITEM_LIKE + ")L" + ITEM_ENTITY + ";")
                        || call.desc.equals("(" + STACK_DESC + ")L" + ITEM_ENTITY + ";"));
    }

    private static boolean guardedShrink(
            MethodNode method,
            Frame<SourceValue>[] frames,
            SourceValue receiver,
            List<InputCheck> checks,
            int shrink) {
        for (var check : checks) {
            if (receiver.insns.isEmpty() || !receiver.insns.equals(check.stack().insns)) continue;
            int branch = method.instructions.indexOf(check.call()) + 1;
            while (branch < shrink && method.instructions.get(branch).getOpcode() < 0) branch++;
            if (branch >= shrink
                    || !(method.instructions.get(branch) instanceof JumpInsnNode jump)
                    || (jump.getOpcode() != Opcodes.IFEQ && jump.getOpcode() != Opcodes.IFNE)
                    || frames[branch] == null
                    || !arg(frames[branch], 1, 0).insns.contains(check.call())) continue;
            int label = method.instructions.indexOf(jump.label);
            int match = jump.getOpcode() == Opcodes.IFNE ? label : branch + 1;
            int miss = jump.getOpcode() == Opcodes.IFNE ? branch + 1 : label;
            if (reaches(method, match, shrink) && !reaches(method, miss, shrink)) return true;
        }
        return false;
    }

    private static boolean reaches(MethodNode method, int start, int wanted) {
        var pending = new java.util.ArrayDeque<Integer>();
        var seen = new BitSet(method.instructions.size());
        pending.add(start);
        while (!pending.isEmpty()) {
            int index = pending.remove();
            if (index == wanted) return true;
            if (index >= method.instructions.size() || seen.get(index)) continue;
            seen.set(index);
            var op = method.instructions.get(index);
            if (op instanceof JumpInsnNode jump) {
                pending.add(method.instructions.indexOf(jump.label));
                if (op.getOpcode() == Opcodes.GOTO) continue;
            } else if (op instanceof TableSwitchInsnNode table) {
                pending.add(method.instructions.indexOf(table.dflt));
                table.labels.forEach(label -> pending.add(method.instructions.indexOf(label)));
                continue;
            } else if (op instanceof LookupSwitchInsnNode lookup) {
                pending.add(method.instructions.indexOf(lookup.dflt));
                lookup.labels.forEach(label -> pending.add(method.instructions.indexOf(label)));
                continue;
            }
            if ((op.getOpcode() >= Opcodes.IRETURN && op.getOpcode() <= Opcodes.RETURN)
                    || op.getOpcode() == Opcodes.ATHROW) continue;
            pending.add(index + 1);
        }
        return false;
    }

    private static final class Origins extends SourceInterpreter {
        Origins() {
            super(Opcodes.ASM9);
        }

        @Override
        public SourceValue copyOperation(AbstractInsnNode op, SourceValue value) {
            return value;
        }

        @Override
        public SourceValue unaryOperation(AbstractInsnNode op, SourceValue value) {
            return op.getOpcode() == Opcodes.CHECKCAST ? value : super.unaryOperation(op, value);
        }

        @Override
        public SourceValue naryOperation(AbstractInsnNode op, List<? extends SourceValue> values) {
            if (op instanceof MethodInsnNode call && !values.isEmpty()) {
                if (call.owner.equals("net/minecraftforge/registries/RegistryObject")
                        && call.name.equals("get")) return values.get(0);
                if ((call.owner.equals("net/minecraftforge/registries/IForgeRegistry")
                                && call.name.equals("getValue"))
                        || (call.owner.equals(RESOURCE)
                                && call.desc.equals("(Ljava/lang/String;)L" + RESOURCE + ";")))
                    return values.get(values.size() - 1);
                if (call.owner.equals(STACK)
                        && name(call, "copy", "m_41777_")
                        && call.desc.equals("()" + STACK_DESC)) return values.get(0);
            }
            return super.naryOperation(op, values);
        }
    }

    private EventOutputFlow() {}
}

package com.thiakil.yamlops;

import com.mojang.datafixers.util.Pair;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.DynamicOps;
import com.mojang.serialization.MapLike;
import com.thiakil.yamlops.util.NodeStrategy;
import it.unimi.dsi.fastutil.objects.Object2ObjectOpenCustomHashMap;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.constructor.Constructor;
import org.yaml.snakeyaml.constructor.ConstructorException;
import org.yaml.snakeyaml.nodes.*;
import org.yaml.snakeyaml.representer.Representer;

import java.math.BigDecimal;
import java.util.*;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.stream.Collector;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@MethodsReturnNonnullByDefault
public class SnakeYamlOps implements DynamicOps<Node> {
    static final DumperOptions DEFAULT_OPTIONS = new DumperOptions();
    static {
        DEFAULT_OPTIONS.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
        DEFAULT_OPTIONS.setLineBreak(DumperOptions.LineBreak.UNIX);
    }
    public static final Collector<NodeTuple, ?, Map<Node, Node>> NODE_TUPLE_COLLECTOR = Collectors.toMap(NodeTuple::getKeyNode, NodeTuple::getValueNode, (m1, m2) -> m2, () -> new Object2ObjectOpenCustomHashMap<>(NodeStrategy.INSTANCE));

    private final Representer representer;
    private final Node EMPTY;
    private final MyConstructor constructor = new MyConstructor();
    private final DumperOptions dumperOptions;

    public SnakeYamlOps(DumperOptions dumperOptions) {
        this.dumperOptions = dumperOptions;
        representer = new Representer(dumperOptions);
        representer.setDefaultFlowStyle(dumperOptions.getDefaultFlowStyle());
        representer.setDefaultScalarStyle(dumperOptions.getDefaultScalarStyle());
        EMPTY = representer.represent(null);
    }

    public SnakeYamlOps() {
        this(DEFAULT_OPTIONS);
    }

    @Override
    public Node empty() {
        return EMPTY;
    }

    @Override
    public <U> U convertTo(DynamicOps<U> outOps, Node input) {
        if (input instanceof MappingNode) {
            return convertMap(outOps, input);
        }
        if (input instanceof SequenceNode) {
            return convertList(outOps, input);
        }
        if (input instanceof ScalarNode scalarNode) {
            if (scalarNode.getTag() == Tag.BOOL) {
                return outOps.createBoolean(getBooleanValue(input).getOrThrow());
            }
            if (scalarNode.getTag() == Tag.INT || scalarNode.getTag() == Tag.FLOAT) {
                return outOps.createNumeric(getNumberValue(input).getOrThrow());
            }
            return outOps.createString(scalarNode.getValue());
        }
        throw new IllegalStateException("Unconvertable Node: "+input);
    }

    private DataResult<ScalarNode> getScalar(Node input) {
        if (input instanceof ScalarNode scalarNode) {
            return DataResult.success(scalarNode);
        }
        return DataResult.error(()->"Not a scalar: "+input);
    }

    private DataResult<SequenceNode> getSequence(Node input) {
        if (input instanceof SequenceNode sequenceNode) {
            return DataResult.success(sequenceNode);
        }
        if (NodeStrategy.INSTANCE.equals(EMPTY, input)) {
            return DataResult.success(new SequenceNode(Tag.MAP, Collections.emptyList(), dumperOptions.getDefaultFlowStyle()));
        }
        return DataResult.error(()->"Not a sequence: "+input);
    }

    private DataResult<MappingNode> getYMap(Node input) {
        if (input instanceof MappingNode mappingNode) {
            return DataResult.success(mappingNode);
        }
        if (NodeStrategy.INSTANCE.equals(EMPTY, input)) {
            return DataResult.success(new MappingNode(Tag.MAP, Collections.emptyList(), dumperOptions.getDefaultFlowStyle()));
        }
        return DataResult.error(()->"Not a MappingNode: "+input);
    }

    @Override
    public DataResult<Number> getNumberValue(Node input) {
        return getScalar(input).flatMap(scalarNode -> {
            if (scalarNode.getTag() != Tag.INT && scalarNode.getTag() != Tag.FLOAT) {
                try {
                    return DataResult.success(new BigDecimal(scalarNode.getValue()));
                } catch (NumberFormatException e) {
                    return DataResult.error(e::getMessage);
                }
            }
            try {
                return DataResult.success((Number) constructor.constructObject(scalarNode));
            } catch (ConstructorException e) {
                return DataResult.error(()->"Deserialisation issue, "+e.getMessage());
            }
        });
    }

    @Override
    public Node createNumeric(Number i) {
        return representer.represent(i);
    }

    @Override
    public DataResult<String> getStringValue(Node input) {
        return getScalar(input).map(ScalarNode::getValue);
    }

    @Override
    public Node createString(String value) {
        return representer.represent(value);
    }

    private DataResult<Node> mergeToList(Node list, Consumer<List<Node>> additionalNodes) {
        return getSequence(list)
                .map(existing -> {
                    List<Node> newValues = new ArrayList<>(existing.getValue());
                    additionalNodes.accept(newValues);
                    return new SequenceNode(Tag.SEQ, newValues, dumperOptions.getDefaultFlowStyle());
                });
    }

    @Override
    public DataResult<Node> mergeToList(Node list, Node value) {
        return mergeToList(list, newValues->newValues.add(value));
    }

    @Override
    public DataResult<Node> mergeToList(Node list, List<Node> values) {
        return mergeToList(list, newValues->newValues.addAll(values));
    }

    private DataResult<Node> mergeToMap(Node map, Consumer<BiConsumer<Node, Node>> valueConsumer) {
        DataResult<Map<Node, Node>> existingValues;
        DumperOptions.FlowStyle flowStyle;
        if (NodeStrategy.INSTANCE.equals(EMPTY, map)) {
            existingValues = DataResult.success(new LinkedHashMap<>());
            flowStyle = dumperOptions.getDefaultFlowStyle();
        } else {
            DataResult<MappingNode> yMap = getYMap(map);
            existingValues = yMap.map(mappingNode ->
                    mappingNode.getValue().stream()
                            .collect(NODE_TUPLE_COLLECTOR)
            );
            flowStyle = yMap.result().map(MappingNode::getFlowStyle).orElse(dumperOptions.getDefaultFlowStyle());
        }
        return existingValues.map(m->{
            valueConsumer.accept(m::put);
            return new MappingNode(Tag.MAP, m.entrySet().stream().map(e->new NodeTuple(e.getKey(), e.getValue())).toList(), flowStyle);
        });
    }

    @Override
    public DataResult<Node> mergeToMap(Node map, Node key, Node value) {
        return mergeToMap(map, consumer->consumer.accept(key, value));
    }

    @Override
    public DataResult<Node> mergeToMap(Node map, MapLike<Node> values) {
        return mergeToMap(map, consumer -> values.entries().forEach(e -> consumer.accept(e.getFirst(), e.getSecond())));
    }

    @Override
    public DataResult<Node> mergeToMap(Node map, Map<Node, Node> values) {
        return mergeToMap(map, values::forEach);
    }

    @Override
    public DataResult<Stream<Pair<Node, Node>>> getMapValues(Node input) {
        return getYMap(input).map(m-> m.getValue().stream()
                        .map(n->new Pair<>(n.getKeyNode(), n.getValueNode()))
                );
    }

    @Override
    public Node createMap(Stream<Pair<Node, Node>> map) {
        return new MappingNode(Tag.MAP, map.map(p->new NodeTuple(p.getFirst(), p.getSecond())).toList(), dumperOptions.getDefaultFlowStyle());
    }

    @Override
    public DataResult<Stream<Node>> getStream(Node input) {
        return getSequence(input).map(s->s.getValue().stream());
    }

    @Override
    public Node createList(Stream<Node> input) {
        return new SequenceNode(Tag.SEQ, input.toList(), dumperOptions.getDefaultFlowStyle());
    }

    @Override
    public Node remove(Node input, String key) {
        return getYMap(input).map(mN-> (Node)new MappingNode(
                Tag.MAP,
                mN.getValue().stream().filter(t->{
                    if (t.getKeyNode() instanceof ScalarNode scalarNode) {
                        return !key.equals(scalarNode.getValue());
                    }
                    return true;
                }).toList(),
                mN.getFlowStyle()
            )
        ).result().orElse(input);
    }

    @Override
    public DataResult<MapLike<Node>> getMap(Node input) {
        return getYMap(input).map(m-> MapLike.forMap(
                m.getValue().stream().collect(NODE_TUPLE_COLLECTOR
                ),
            this)
        );
    }

    @Override
    public Node createBoolean(boolean value) {
        return representer.represent(value);
    }

    @Override
    public DataResult<Boolean> getBooleanValue(Node input) {
        return getScalar(input).flatMap(scalarNode -> {
            scalarNode.setTag(Tag.BOOL);
            scalarNode.setType(boolean.class);
            try {
                return DataResult.success((Boolean) constructor.constructObject(scalarNode));
            } catch (ConstructorException e) {
                return DataResult.error(()->"Deserialisation issue, "+e.getMessage());
            }
        });
    }

    private static class MyConstructor extends Constructor {
        MyConstructor() {
            super(new LoaderOptions());
        }

        @Override
        public Object constructObject(Node node) {
            return super.constructObject(node);
        }
    }
}
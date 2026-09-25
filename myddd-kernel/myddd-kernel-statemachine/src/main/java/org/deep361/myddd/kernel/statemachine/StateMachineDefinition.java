package org.deep361.myddd.kernel.statemachine;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 状态机定义：状态集合 + 转移集合 + 初始/终态，不可变、线程安全。
 *
 * <p><b>定义期校验</b>（登记期或 {@link Builder#build()} 期触发，任一条不满足即抛 {@link IllegalStateException}）：
 * <ol>
 *   <li>必须设置初始状态，且初始状态不得为终态；</li>
 *   <li>同一"源状态 + 事件"不得重复定义转移（否则流转目标不确定）；</li>
 *   <li>每个状态要么有出边、要么被声明为终态——无出边又未声明终态说明状态图写漏了；</li>
 *   <li>终态不得有出边（声明与图结构矛盾）；</li>
 *   <li>所有状态必须从初始状态可达——不可达状态是死代码或事件写错。</li>
 * </ol>
 *
 * <p>校验刻意严格：状态图是业务不变量的一部分，宁可在装配期报错，也不要在生产流转变更时才暴露。
 *
 * @param <S> 状态类型（推荐枚举）
 * @param <E> 事件类型（推荐枚举）
 */
public final class StateMachineDefinition<S, E> {

    private final String name;
    private final S initialState;
    private final Set<S> states;
    private final Set<S> terminalStates;
    private final List<Transition<S, E>> transitions;
    private final Map<S, Map<E, Transition<S, E>>> byStateAndEvent;

    private StateMachineDefinition(Builder<S, E> builder) {
        this.name = builder.name;
        this.initialState = builder.initialState;
        this.terminalStates = Collections.unmodifiableSet(new LinkedHashSet<>(builder.terminalStates));
        this.transitions = List.copyOf(builder.transitions);
        Map<S, Map<E, Transition<S, E>>> index = new LinkedHashMap<>();
        for (Transition<S, E> transition : builder.transitions) {
            index.computeIfAbsent(transition.from(), state -> new LinkedHashMap<>()).put(transition.event(), transition);
        }
        Map<S, Map<E, Transition<S, E>>> immutableIndex = new LinkedHashMap<>();
        index.forEach((state, byEvent) -> immutableIndex.put(state, Collections.unmodifiableMap(byEvent)));
        this.byStateAndEvent = Collections.unmodifiableMap(immutableIndex);

        this.states = Collections.unmodifiableSet(builder.collectStates());
    }

    /** 创建构建器。 */
    public static <S, E> Builder<S, E> builder(String name) {
        return new Builder<>(name);
    }

    /** 状态机名称（日志与诊断用）。 */
    public String name() {
        return name;
    }

    /** 初始状态。 */
    public S initialState() {
        return initialState;
    }

    /** 全部状态（含由转移推断出的状态），按"初始状态 → 显式声明 → 转移出现顺序"排列。 */
    public Set<S> states() {
        return states;
    }

    /** 终态集合，按声明顺序。 */
    public Set<S> terminalStates() {
        return terminalStates;
    }

    /** 全部转移，按声明顺序。 */
    public List<Transition<S, E>> transitions() {
        return transitions;
    }

    /** 全部事件（去重），按转移声明顺序。 */
    public Set<E> events() {
        Set<E> events = new LinkedHashSet<>();
        transitions.forEach(transition -> events.add(transition.event()));
        return Collections.unmodifiableSet(events);
    }

    /**
     * 是否为终态。
     *
     * @throws NullPointerException state 为 null
     */
    public boolean isTerminal(S state) {
        Objects.requireNonNull(state, "state 不能为 null");
        return terminalStates.contains(state);
    }

    /**
     * 该状态可触发的事件，按转移声明顺序；终态返回空集。
     *
     * @throws NullPointerException state 为 null
     */
    public Set<E> eventsFrom(S state) {
        Objects.requireNonNull(state, "state 不能为 null");
        Map<E, Transition<S, E>> byEvent = byStateAndEvent.get(state);
        return byEvent == null
                ? Set.of()
                : Collections.unmodifiableSet(new LinkedHashSet<>(byEvent.keySet()));
    }

    /**
     * 查找转移。
     *
     * @throws NullPointerException from 或 event 为 null
     */
    public Optional<Transition<S, E>> transition(S from, E event) {
        Objects.requireNonNull(from, "from 不能为 null");
        Objects.requireNonNull(event, "event 不能为 null");
        return Optional.ofNullable(byStateAndEvent.getOrDefault(from, Map.of()).get(event));
    }

    /**
     * 取转移，不存在则抛 {@link StateMachineException}（错误码 {@code ILLEGAL_STATE}）。
     *
     * @throws NullPointerException  from 或 event 为 null
     * @throws StateMachineException 当前状态不允许执行该事件
     */
    public Transition<S, E> requireTransition(S from, E event) {
        return transition(from, event).orElseThrow(() -> new StateMachineException(from, event));
    }

    /**
     * 目标状态。
     *
     * @throws NullPointerException  from 或 event 为 null
     * @throws StateMachineException 当前状态不允许执行该事件
     */
    public S targetState(S from, E event) {
        return requireTransition(from, event).to();
    }

    /** 从初始状态可达的全部状态（校验通过后等于 {@link #states()}）。 */
    public Set<S> reachableStates() {
        Set<S> visited = new LinkedHashSet<>();
        Deque<S> queue = new ArrayDeque<>();
        visited.add(initialState);
        queue.add(initialState);
        while (!queue.isEmpty()) {
            S state = queue.poll();
            eventsFrom(state).forEach(event -> {
                S next = byStateAndEvent.get(state).get(event).to();
                if (visited.add(next)) {
                    queue.add(next);
                }
            });
        }
        return Collections.unmodifiableSet(visited);
    }

    /** 状态机定义构建器：状态由 {@code initial}/{@code state}/{@code terminal}/转移自动收集。 */
    public static final class Builder<S, E> {

        private final String name;
        private final Set<S> declaredStates = new LinkedHashSet<>();
        private final Set<S> terminalStates = new LinkedHashSet<>();
        private final List<Transition<S, E>> transitions = new ArrayList<>();
        private S initialState;

        private Builder(String name) {
            this.name = Objects.requireNonNull(name, "name 不能为 null");
            if (name.isBlank()) {
                throw new IllegalArgumentException("name 不能为空白");
            }
        }

        /**
         * 设置初始状态（同时纳入状态集合）。
         *
         * @throws NullPointerException  state 为 null
         * @throws IllegalStateException 已设置过初始状态（重复设置会让先设置的初始状态静默退化为普通状态）
         */
        public Builder<S, E> initial(S state) {
            Objects.requireNonNull(state, "初始状态不能为 null");
            if (initialState != null) {
                throw new IllegalStateException("状态机 " + name + " 已设置初始状态 " + initialState
                        + "，不允许重复设置为 " + state);
            }
            this.initialState = state;
            this.declaredStates.add(state);
            return this;
        }

        /**
         * 显式声明状态：纳入状态集合与图校验。
         *
         * <p>{@code initial}/{@code terminal}/转移端点都会自动收集状态，本方法用于"先声明全部状态、再逐个补充转移"
         * 的写法，便于对照状态图核对是否有状态被写漏。
         */
        public Builder<S, E> state(S state) {
            this.declaredStates.add(Objects.requireNonNull(state, "state 不能为 null"));
            return this;
        }

        /** 声明终态。 */
        public Builder<S, E> terminal(S state) {
            this.terminalStates.add(Objects.requireNonNull(state, "终态不能为 null"));
            return this;
        }

        /** 定义无守卫转移（等价于守卫恒真）。 */
        public Builder<S, E> transition(S from, E event, S to) {
            return transition(from, event, to, context -> true);
        }

        /** 定义带守卫转移。 */
        public Builder<S, E> transition(S from, E event, S to, TransitionGuard<S, E> guard) {
            Transition<S, E> transition = new Transition<>(from, event, to, guard);
            boolean duplicated = transitions.stream().anyMatch(existing -> existing.from().equals(transition.from())
                    && existing.event().equals(transition.event()));
            if (duplicated) {
                throw new IllegalStateException("状态机 " + name + " 存在重复转移定义：" + transition.from() + " --"
                        + transition.event() + "--> 已定义，不允许再定义指向 " + transition.to() + " 的转移");
            }
            transitions.add(transition);
            declaredStates.add(transition.from());
            declaredStates.add(transition.to());
            return this;
        }

        /**
         * 定义涉及的全部状态：初始状态 → 显式声明 → 终态声明 → 转移端点，顺序即 {@link #states()} 的顺序。
         *
         * <p>终态声明必须单独纳入：{@code terminal(...)} 不写入 {@code declaredStates}，只在终态集合中出现的状态
         * （如"由转移推断出的目标状态"之外的孤立终态）若被漏掉，"无出边又未声明终态"与"不可达状态"两条校验都会
         * 静默放过它——状态图写漏必须报错，不能因为收集口径不一致而漏检。
         */
        private Set<S> collectStates() {
            Set<S> allStates = new LinkedHashSet<>();
            allStates.add(initialState);
            allStates.addAll(declaredStates);
            allStates.addAll(terminalStates);
            transitions.forEach(transition -> {
                allStates.add(transition.from());
                allStates.add(transition.to());
            });
            return allStates;
        }

        /**
         * 完成定义并执行全部图校验。
         *
         * @throws IllegalStateException 初始状态缺失/为终态、无出边又未声明终态、终态有出边、存在不可达状态
         */
        public StateMachineDefinition<S, E> build() {
            if (initialState == null) {
                throw new IllegalStateException("状态机 " + name + " 未设置初始状态：请调用 initial(...)");
            }
            if (terminalStates.contains(initialState)) {
                throw new IllegalStateException("状态机 " + name + " 的初始状态 " + initialState + " 被声明为终态，"
                        + "状态机无法启动");
            }
            Set<S> allStates = collectStates();
            for (S state : allStates) {
                boolean hasOutgoing = transitions.stream().anyMatch(transition -> transition.from().equals(state));
                boolean terminal = terminalStates.contains(state);
                if (!hasOutgoing && !terminal) {
                    throw new IllegalStateException("状态机 " + name + " 的状态 " + state
                            + " 既无出边也未声明为终态：请补充转移或调用 terminal(...)");
                }
                if (hasOutgoing && terminal) {
                    throw new IllegalStateException("状态机 " + name + " 的终态 " + state
                            + " 不应有出边，但存在以它为源的转移：请删除出边或取消终态声明");
                }
            }
            StateMachineDefinition<S, E> definition = new StateMachineDefinition<>(this);
            Set<S> reachable = definition.reachableStates();
            if (reachable.size() != allStates.size()) {
                String unreachable = allStates.stream()
                        .filter(state -> !reachable.contains(state))
                        .map(String::valueOf)
                        .collect(Collectors.joining(", "));
                throw new IllegalStateException("状态机 " + name + " 存在从初始状态 " + initialState
                        + " 不可达的状态：" + unreachable + "：请检查转移定义是否写漏");
            }
            return definition;
        }
    }
}

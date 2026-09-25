package tools.jackson.databind;

/**
 * 架构规则夹具用的占位类型：只提供 {@code tools.jackson..} 这个全限定名（Jackson 3 的新包名），
 * 让夹具类在字节码里产生一条指向该家族的依赖（ArchUnit 按包名模式匹配，不依赖真实实现）。
 *
 * <p>不是 Jackson 的副本，也不参与任何生产代码，仅存在于测试源码树。
 */
public class ObjectMapper {

    public String writeValueAsString(Object value) {
        return String.valueOf(value);
    }
}

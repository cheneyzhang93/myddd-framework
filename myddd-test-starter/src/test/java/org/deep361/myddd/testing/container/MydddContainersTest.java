package org.deep361.myddd.testing.container;

import com.redis.testcontainers.RedisContainer;
import org.deep361.myddd.testing.image.DevboxImages;
import org.junit.jupiter.api.Test;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.Container;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.mysql.MySQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 容器持有类的形状与镜像兼容性契约。全部不需要 Docker：只验类形状、注解与镜像名可接受性
 * （构造容器不碰 Docker，实测；启动容器才碰）。
 */
class MydddContainersTest {

    private record Holder(Class<?> type, String field, Class<?> containerType, String image) {
    }

    private static final List<Holder> HOLDERS = List.of(
            new Holder(MydddMysqlContainer.class, "MYSQL", MySQLContainer.class, DevboxImages.MYSQL),
            new Holder(MydddRedisContainer.class, "REDIS", RedisContainer.class, DevboxImages.REDIS),
            new Holder(MydddKafkaContainer.class, "KAFKA", KafkaContainer.class, DevboxImages.KAFKA));

    @Test
    void should_declare_one_annotated_static_container_per_holder() {
        for (Holder holder : HOLDERS) {
            List<Field> annotated = Arrays.stream(holder.type().getDeclaredFields())
                    .filter(field -> field.isAnnotationPresent(ServiceConnection.class))
                    .toList();
            assertThat(annotated).as("%s 必须只声明一个 @ServiceConnection 容器", holder.type().getSimpleName())
                    .hasSize(1);
            Field field = annotated.get(0);
            assertThat(field.getName()).isEqualTo(holder.field());
            assertThat(Modifier.isStatic(field.getModifiers())).as("%s 必须是 static", holder.field()).isTrue();
            assertThat(Modifier.isPublic(field.getModifiers())).as("%s 必须 public 供 IT 复用", holder.field()).isTrue();
            assertThat(field.isAnnotationPresent(org.testcontainers.junit.jupiter.Container.class))
                    .as("%s 必须同时标 @Container（JUnit 侧生命周期）", holder.field()).isTrue();
            assertThat(field.getType()).isEqualTo(holder.containerType());
        }
    }

    @Test
    void should_use_the_testcontainers_2_coordinate_not_the_deprecated_one() throws Exception {
        for (Holder holder : HOLDERS) {
            Class<?> containerType = holder.type().getDeclaredField(holder.field()).getType();
            assertThat(containerType.getPackageName())
                    .as("旧坐标 org.testcontainers.containers.* 在 2.0.5 已废弃")
                    .isNotEqualTo("org.testcontainers.containers");
            assertThat(Container.class).as("必须是可被 @ServiceConnection 处理的容器").isAssignableFrom(containerType);
            assertThat(containerType.isAnnotationPresent(Deprecated.class)).isFalse();
        }
    }

    @Test
    void should_keep_holders_non_instantiable() {
        for (Holder holder : HOLDERS) {
            assertThat(Modifier.isFinal(holder.type().getModifiers())).as("%s 必须 final", holder.type().getSimpleName())
                    .isTrue();
            assertThat(holder.type().getDeclaredConstructors())
                    .as("%s 不该被实例化（它只是字段容器）", holder.type().getSimpleName())
                    .allSatisfy(constructor -> assertThat(Modifier.isPrivate(constructor.getModifiers())).isTrue());
        }
    }

    @Test
    void should_point_every_container_at_the_devbox_image() throws Exception {
        for (Holder holder : HOLDERS) {
            Container<?> container = (Container<?>) holder.type().getDeclaredField(holder.field()).get(null);
            assertThat(declaredImage(container)).isEqualTo(holder.image());
        }
    }

    /**
     * 取容器构造时声明的镜像名。不许改用 {@code container.getDockerImageName()}：实测该方法会走
     * {@code RemoteDockerImage.resolve()}（先查本地镜像列表），因此要求 Docker 守护进程，在没有
     * Docker 的机器上抛 {@code ContainerFetchException}，破坏"无 Docker 也能 verify 全绿"。
     * 构造期写入的 {@code imageNameFuture} 是 {@code CompletableFuture.completedFuture(DockerImageName)}，
     * 读它不碰 Docker，且读的是替换前（我们自己声明的）名字，正是本用例要钉的对象。
     */
    private static String declaredImage(Container<?> container) throws Exception {
        Field imageField = null;
        for (Class<?> owner = container.getClass(); owner != null; owner = owner.getSuperclass()) {
            try {
                imageField = owner.getDeclaredField("image");
                break;
            } catch (NoSuchFieldException absent) {
                // 继续向上找：GenericContainer 才是声明者
            }
        }
        assertThat(imageField).as("容器必须持有 Testcontainers 声明的 image 字段").isNotNull();
        imageField.setAccessible(true);
        Object image = imageField.get(container);
        var futureField = image.getClass().getDeclaredField("imageNameFuture");
        futureField.setAccessible(true);
        Object declared = futureField.get(image);
        return ((java.util.concurrent.Future<?>) declared).get().toString();
    }

    @Test
    void should_start_each_container_once_via_a_static_field() throws Exception {
        for (Holder holder : HOLDERS) {
            Field field = holder.type().getDeclaredField(holder.field());
            assertThat(field.get(null)).isSameAs(field.get(null));
        }
    }

    @Test
    void should_have_devbox_images_natively_accepted_by_their_container_class() {
        assertThat(new MySQLContainer(DockerImageName.parse(DevboxImages.MYSQL))).isNotNull();
        assertThat(new RedisContainer(DockerImageName.parse(DevboxImages.REDIS))).isNotNull();
        assertThat(new KafkaContainer(DockerImageName.parse(DevboxImages.KAFKA))).isNotNull();
    }

    @Test
    void should_reject_non_native_image_names_at_construction() {
        // 取证方向：一旦 devbox 镜像换成 bitnami/confluent 系，必须先显式声明兼容性，而不是运行期撞墙
        assertThatThrownBy(() -> new MySQLContainer(DockerImageName.parse("bitnami/mysql:8.4")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("compatible substitute");
        assertThatThrownBy(() -> new KafkaContainer(DockerImageName.parse("confluentinc/cp-kafka:7.7.1")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("compatible substitute");
    }
}

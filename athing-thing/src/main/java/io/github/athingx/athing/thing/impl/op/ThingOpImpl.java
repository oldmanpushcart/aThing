package io.github.athingx.athing.thing.impl.op;

import io.github.athingx.athing.common.gson.GsonFactory;
import io.github.athingx.athing.thing.api.ThingPath;
import io.github.athingx.athing.thing.api.op.*;
import io.github.athingx.athing.thing.impl.util.TokenSequencer;
import org.eclipse.paho.client.mqttv3.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.function.Function;

import static io.github.athingx.athing.thing.api.util.CompletableFutureUtils.*;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.concurrent.TimeUnit.MILLISECONDS;

/**
 * 设备操作实现
 */
public class ThingOpImpl implements ThingOp {

    private final Logger logger = LoggerFactory.getLogger(getClass());
    private final ThingPath path;
    private final IMqttAsyncClient client;
    private final ExecutorService executor;
    private final TokenSequencer sequencer = new TokenSequencer();

    /**
     * 设备操作实现
     *
     * @param path     设备路径
     * @param client   MQTT客户端
     * @param executor 线程池
     */
    public ThingOpImpl(ThingPath path, IMqttAsyncClient client, ExecutorService executor) {
        this.path = path;
        this.client = client;
        this.executor = executor;
    }

    @Override
    public String genToken() {
        return sequencer.next();
    }

    @Override
    public <V> CompletableFuture<Void> post(PubPort<? super V> pub, V data) {
        final var topic = pub.topic(data);
        final var opData = pub.encode(genToken(), data);
        final var qos = pub.qos();
        return _mqtt_post(topic, qos, opData)
                .whenComplete(whenCompleted(
                        v -> logger.debug("{}/op/post success; topic={};token={};", path, topic, opData.token()),
                        ex -> logger.warn("{}/op/post failure; topic={};token={};", path, topic, opData.token(), ex)
                ));
    }

    private CompletableFuture<Void> _mqtt_post(String topic, int qos, OpData opData) {

        final var encode = Function.<OpData>identity()

                // 修复alink协议的BUG：当回传的类型为OpReply时，器中的data如果为null，必须以{}的形式回传
                .andThen(data -> {
                    if (data instanceof OpReply<?> reply)
                        return new OpReply<>(
                                reply.token(),
                                reply.code(),
                                reply.desc(),
                                new HashMap<>()
                        );
                    return data;
                })

                // 编码为JSON字符串
                .andThen(data -> GsonFactory.getGson().toJson(data))

                // 编码为UTF-8字节数组
                .andThen(json -> json.getBytes(UTF_8));


        // MQTT: message
        final var message = new MqttMessage();
        message.setQos(qos);
        message.setPayload(encode.apply(opData));

        // MQTT: publish
        return executeFuture(new MqttActionFuture<>(), postF -> client.publish(topic, message, new Object(), postF));

    }

    @Override
    public <V> CompletableFuture<ThingBind> bindConsumer(final SubPort<? extends V> sub,
                                                         final BiConsumer<String, ? super V> consumeFn) {

        // ThingBind: init
        final ThingBind bind = () -> _mqtt_unbind(sub)
                .whenComplete(whenCompleted(
                        v -> logger.debug("{}/op/consume/unbind success; express={};", path, sub.express()),
                        ex -> logger.warn("{}/op/consume/unbind failure; express={};", path, sub.express(), ex)
                ));

        // MQTT: bind
        return _mqtt_bind("consume", sub, consumeFn)
                .thenApply(unused -> bind)
                .whenComplete(whenCompleted(
                        v -> logger.debug("{}/op/consume/bind success; express={};", path, sub.express()),
                        ex -> logger.warn("{}/op/consume/bind failure; express={};", path, sub.express(), ex)
                ));
    }

    private <V> CompletableFuture<Void> _mqtt_unbind(final SubPort<? extends V> sub) {
        return executeFuture(new MqttActionFuture<>(), unbindF
                -> client.unsubscribe(sub.express(), new Object(), unbindF));
    }

    private <V> CompletableFuture<Void> _mqtt_bind(final String action,
                                                   final SubPort<? extends V> sub,
                                                   final BiConsumer<String, ? super V> consumeFn) {

        // MQTT: listener
        final IMqttMessageListener listener = (topic, message) ->
                executor.execute(() -> {

                    // 解码消息
                    final V data;
                    try {
                        data = sub.decode(topic, message.getPayload());
                    } catch (Throwable cause) {
                        logger.warn("{}/op/{} decode failure! topic={};", path, action, topic, cause);
                        return;
                    }

                    // 解码消息结果为空，说明本次处理需要丢弃该消息
                    if (Objects.isNull(data)) {
                        logger.debug("{}/op/{} decode null! topic={};", path, action, topic);
                        return;
                    }

                    // 消费消息
                    try {
                        consumeFn.accept(topic, data);
                    } catch (Throwable cause) {
                        logger.warn("{}/op/{} failure! topic={};", path, action, topic, cause);
                    }

                });

        // MQTT: subscribe
        return executeFuture(new MqttActionFuture<>(), bindF ->
                client.subscribe(sub.express(), sub.qos(), new Object(), bindF, listener));
    }

    @Override
    public <T extends OpData, R>
    CompletableFuture<ThingBind> bindServices(final SubPort<? extends T> sub,
                                              final PubPort<? super R> pub,
                                              final BiFunction<String, ? super T, CompletableFuture<? extends R>> serviceFn) {

        // ThingBind: init
        final ThingBind bind = () -> _mqtt_unbind(sub)
                .whenComplete(whenCompleted(
                        v -> logger.debug("{}/op/service/unbind success; express={};", path, sub.express()),
                        ex -> logger.warn("{}/op/service/unbind failure; express={};", path, sub.express(), ex)
                ));

        // Consumer: service
        final BiConsumer<String, ? super T> serviceConsumer = new BiConsumer<>() {

            private CompletableFuture<? extends R> executeServiceFn(String subTopic, T request) {
                try {
                    return serviceFn.apply(subTopic, request);
                } catch (Throwable cause) {
                    return CompletableFuture.failedFuture(cause);
                }
            }

            @Override
            public void accept(String subTopic, T request) {
                executeServiceFn(subTopic, request)
                        .whenComplete(whenCompleted(
                                v -> logger.debug("{}/op/service/execute success! token={};request-topic={};", path, request.token(), subTopic),
                                ex -> logger.warn("{}/op/service/execute failure! token={};request-topic={};", path, request.token(), subTopic, ex)
                        ))
                        .whenComplete(whenSuccessfully(response -> {
                            final var pubTopic = pub.topic(response);
                            final var pubQos = pub.qos();
                            final var pubOpData = pub.encode(request.token(), response);
                            _mqtt_post(pubTopic, pubQos, pubOpData)
                                    .whenComplete(whenCompleted(
                                            v -> logger.debug("{}/op/service/response success! token={};response-topic={};", path, pubOpData.token(), pubTopic),
                                            ex -> logger.warn("{}/op/service/response failure! token={};response-topic={};", path, pubOpData.token(), pubTopic, ex)
                                    ));
                        }));
            }
        };

        // MQTT: bind
        return _mqtt_bind("service/request", sub, serviceConsumer)
                .thenApply(unused -> bind)
                .whenComplete(whenCompleted(
                        v -> logger.debug("{}/op/service/bind success; express={};", path, sub.express()),
                        ex -> logger.warn("{}/op/service/bind failure; express={};", path, sub.express(), ex)
                ));

    }

    @Override
    public <T, R extends OpData> CompletableFuture<? extends ThingCall<? super T, ? extends R>> bindCaller(
            final PubPort<? super T> pub,
            final SubPort<? extends R> sub
    ) {

        final var tokenFutureMap = new ConcurrentHashMap<String, CompletableFuture<R>>();

        // ThingCall: init
        final var call = new ThingCall<T, R>() {

            @Override
            public CompletableFuture<R> call(Option option, T data) {

                // 请求主题
                final var topic = pub.topic(data);
                final var qos = pub.qos();
                final var opData = pub.encode(genToken(), data);

                // 生成调用存根并存入缓存
                final var callF = new CompletableFuture<R>();
                tokenFutureMap.put(opData.token(), callF);

                // 发起请求，如果请求失败则让调用Future失败
                _mqtt_post(topic, qos, opData)
                        .whenComplete(whenExceptionally(callF::completeExceptionally))
                        .whenComplete(whenCompleted(
                                v -> logger.debug("{}/op/call/request success; topic={};token={};", path, topic, opData.token()),
                                ex -> logger.warn("{}/op/call/request failure; topic={};token={};", path, topic, opData.token(), ex)
                        ));

                // 返回调用future
                return callF
                        .orTimeout(option.timeoutMs(), MILLISECONDS)
                        .whenComplete((v, ex) -> tokenFutureMap.remove(opData.token()))
                        .whenComplete(whenCompleted(
                                v -> logger.debug("{}/op/call/response success; topic={};token={};", path, topic, opData.token()),
                                ex -> logger.warn("{}/op/call/response failure; topic={};token={};", path, topic, opData.token(), ex)
                        ));
            }

            @Override
            public CompletableFuture<R> call(Option option, Function<String, ? extends T> encoder) {
                return call(option, encoder.apply(genToken()));
            }

            @Override
            public CompletableFuture<Void> unbind() {
                return _mqtt_unbind(sub)
                        .whenComplete(whenCompleted(
                                v -> logger.debug("{}/op/call/unbind success; express={};", path, sub.express()),
                                ex -> logger.warn("{}/op/call/unbind failure; express={};", path, sub.express(), ex)
                        ));
            }
        };

        // 绑定
        final var bindF = _mqtt_bind("call/response", sub, (topic, data) -> {
            final var token = data.token();
            final var callF = tokenFutureMap.remove(token);
            if (Objects.isNull(callF)) {
                logger.warn("{}/op/call/response received; but none token match, maybe timeout! topic={};token={};", path, topic, data.token());
            } else if (!callF.complete(data)) {
                logger.warn("{}/op/call/response received; but assign failure, maybe expired. topic={};token={};", path, topic, data.token());
            } else {
                logger.debug("{}/op/call/response received; topic={};token={};", path, topic, data.token());
            }
        });

        // 返回绑定
        return bindF
                .thenApply(unused -> call)
                .whenComplete(whenCompleted(
                        v -> logger.debug("{}/op/call/bind success; express={};", path, sub.express()),
                        ex -> logger.warn("{}/op/call/bind failure; express={};", path, sub.express(), ex)
                ));

    }


    /**
     * MQTT回调-Future封装
     *
     * @param <T> 成功返回数据类型
     */
    private static class MqttActionFuture<T> extends CompletableFuture<T> implements IMqttActionListener {

        private final T target;

        private MqttActionFuture() {
            this(null);
        }

        private MqttActionFuture(T target) {
            this.target = target;
        }

        @Override
        public void onSuccess(IMqttToken asyncActionToken) {
            complete(target);
        }

        @Override
        public void onFailure(IMqttToken asyncActionToken, Throwable exception) {
            completeExceptionally(exception);
        }

    }

}

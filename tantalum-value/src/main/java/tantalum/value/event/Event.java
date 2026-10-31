package tantalum.value.event;

/** 事件。不是封闭类型，插件可以注册自己的事件；已写入 journal 的类型不改语义，新语义用新版本。 */
public interface Event {

    String type();

    int version();
}

package tantalum.value.decision;

import tantalum.value.id.RunRef;

/** 对外通知。不写 journal，回放时重新算出。不是封闭类型，插件可以定义自己的事实。 */
public interface Fact {

    String type();

    RunRef run();
}

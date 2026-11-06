package tantalum.shell.route;

import java.util.Map;
import tantalum.shell.model.ModelClient;

/**
 * 按路线名换掉客户端（测试和嵌入使用，如脚本化模型）：这些路线不读密钥、不调模型接口，列表只按配置给（{@link ConfigModels}）。
 * 路线本身照样要在配置里写。
 */
public record RouteClients(Map<String, ModelClient> clients) {

    public RouteClients {
        clients = Map.copyOf(clients);
    }

    public static RouteClients of(String route, ModelClient client) {
        return new RouteClients(Map.of(route, client));
    }
}

/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.alibaba.dubbo.rpc.cluster.router.condition;

import com.alibaba.dubbo.common.Constants;
import com.alibaba.dubbo.common.URL;
import com.alibaba.dubbo.common.logger.Logger;
import com.alibaba.dubbo.common.logger.LoggerFactory;
import com.alibaba.dubbo.common.utils.NetUtils;
import com.alibaba.dubbo.common.utils.StringUtils;
import com.alibaba.dubbo.common.utils.UrlUtils;
import com.alibaba.dubbo.rpc.Invocation;
import com.alibaba.dubbo.rpc.Invoker;
import com.alibaba.dubbo.rpc.RpcException;
import com.alibaba.dubbo.rpc.cluster.Router;
import com.alibaba.dubbo.rpc.cluster.router.AbstractRouter;

import java.text.ParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * ConditionRouter
 * 该类是基于条件表达式的路由实现类。关于给予条件表达式的路由规则，可以查看官方文档：http://dubbo.apache.org/zh-cn/docs/user/demos/routing-rule-deprecated.html
 *
 * route://0.0.0.0/com.foo.BarService?group=foo&version=1.0&category=routers&dynamic=false&enabled=true&force=false&runtime=false&priority=1&rule=" + URL.encode("host = 10.20.153.10 => host = 10.20.153.11")
 * 1.  route:// 表示路由规则的类型，支持条件路由规则(condition://)和脚本路由规则(script://)，可扩展，必填。
 * 2.  0.0.0.0 表示对所有 IP 地址生效，如果只想对某个 IP 的生效，请填入具体 IP，必填。
 * 3.  com.foo.BarService 表示只对指定服务生效，必填。
 * 4.  group=foo 对指定服务的指定group生效，不填表示对未配置group的指定服务生效
 * 5.  version=1.0对指定服务的指定version生效，不填表示对未配置version的指定服务生效
 * 6.  category=routers 表示该数据为动态配置类型，必填。
 * 7.  dynamic=false 表示该数据为持久数据，当注册方退出时，数据依然保存在注册中心，必填。
 * 8.  enabled=true 覆盖规则是否生效，可不填，缺省生效。
 * 9.  force=false 当路由结果为空时，是否强制执行，如果不强制执行，路由结果为空的路由规则将自动失效，可不填，缺省为 false。
 * 10. runtime=false 是否在每次调用时执行路由规则，否则只在提供者地址列表变更时预先执行并缓存结果，调用时直接从缓存中获取路由结果。如果用了参数路由，必须设为 true，需要注意设置会影响调用的性能，可不填，缺省为 false。
 * 11. priority=1 路由规则的优先级，用于排序，优先级越大越靠前执行，可不填，缺省为 0。
 * 12. rule=URL.encode("host = 10.20.153.10 => host = 10.20.153.11") 表示路由规则的内容，必填。
 *
 */
public class ConditionRouter extends AbstractRouter {

    private static final Logger logger = LoggerFactory.getLogger(ConditionRouter.class);
    private static final int DEFAULT_PRIORITY = 2;

    /**
     * 分组正则匹配
     */
    private static Pattern ROUTE_PATTERN = Pattern.compile("([&!=,]*)\\s*([^&!=,\\s]+)");

    /**
     * 当路由结果为空时，是否强制执行，如果强制执行则直接返回路由结果（返回空的集合），如果不强制执行，路由结果为空的路由规则将自动失效，即仍然返回路由之前的invokers。可不填，缺省为 false 。
     */
    private final boolean force;

    /**
     * 消费者匹配条件集合，通过解析【条件表达式 rule 的 `=>` 之前半部分】
     */
    private final Map<String, MatchPair> whenCondition;

    /**
     * 提供者地址列表的过滤条件，通过解析【条件表达式 rule 的 `=>` 之后半部分】
     */
    private final Map<String, MatchPair> thenCondition;

    public ConditionRouter(URL url) {
        this.url = url;
        // 获得优先级配置
        this.priority = url.getParameter(Constants.PRIORITY_KEY, DEFAULT_PRIORITY);
        // 获得是否强制执行配置
        this.force = url.getParameter(Constants.FORCE_KEY, false);
        try {
            // 获得规则
            String rule = url.getParameterAndDecoded(Constants.RULE_KEY);
            if (rule == null || rule.trim().length() == 0) {
                throw new IllegalArgumentException("Illegal route rule!");
            }
            rule = rule.replace("consumer.", "").replace("provider.", "");
            int i = rule.indexOf("=>");
            // 分割消费者和提供者规则
            String whenRule = i < 0 ? null : rule.substring(0, i).trim();
            String thenRule = i < 0 ? rule.trim() : rule.substring(i + 2).trim();
            Map<String, MatchPair> when = StringUtils.isBlank(whenRule) || "true".equals(whenRule) ? new HashMap<String, MatchPair>() : parseRule(whenRule);
            Map<String, MatchPair> then = StringUtils.isBlank(thenRule) || "false".equals(thenRule) ? null : parseRule(thenRule);
            // NOTE: It should be determined on the business level whether the `When condition` can be empty or not.
            this.whenCondition = when;
            this.thenCondition = then;
        } catch (ParseException e) {
            throw new IllegalStateException(e.getMessage(), e);
        }
    }

    /**
     * 该方法是根据规则解析路由配置内容。具体的可以参照官网的配置规则来解读这里每一个分割取值作为条件的过程。
     */
    private static Map<String, MatchPair> parseRule(String rule)
            throws ParseException {
        Map<String, MatchPair> condition = new HashMap<String, MatchPair>();
        // 如果规则为空，则直接返回空
        if (StringUtils.isBlank(rule)) {
            return condition;
        }
        // Key-Value pair, stores both match and mismatch conditions
        MatchPair pair = null;
        // Multiple values
        Set<String> values = null;
        // 正则表达式匹配
        final Matcher matcher = ROUTE_PATTERN.matcher(rule);
        // 一个一个匹配
        // Try to match one by one
        while (matcher.find()) {
            String separator = matcher.group(1);
            String content = matcher.group(2);
            // Start part of the condition expression.
            // 开始条件表达式
            if (separator == null || separator.length() == 0) {
                pair = new MatchPair();
                // 保存条件
                condition.put(content, pair);
            }
            // The KV part of the condition expression
            else if ("&".equals(separator)) {
                // 把参数的条件表达式放入condition
                if (condition.get(content) == null) {
                    pair = new MatchPair();
                    condition.put(content, pair);
                } else {
                    pair = condition.get(content);
                }
            }
            // The Value in the KV part.
            // 把值放入values
            else if ("=".equals(separator)) {
                if (pair == null)
                    throw new ParseException("Illegal route rule \""
                            + rule + "\", The error char '" + separator
                            + "' at index " + matcher.start() + " before \""
                            + content + "\".", matcher.start());

                values = pair.matches;
                values.add(content);
            }
            // The Value in the KV part.
            // 把不等于的条件限制也放入values
            else if ("!=".equals(separator)) {
                if (pair == null)
                    throw new ParseException("Illegal route rule \""
                            + rule + "\", The error char '" + separator
                            + "' at index " + matcher.start() + " before \""
                            + content + "\".", matcher.start());

                values = pair.mismatches;
                values.add(content);
            }
            // The Value in the KV part, if Value have more than one items.
            // 如果以,分隔的也放入values
            else if (",".equals(separator)) { // Should be seperateed by ','
                if (values == null || values.isEmpty())
                    throw new ParseException("Illegal route rule \""
                            + rule + "\", The error char '" + separator
                            + "' at index " + matcher.start() + " before \""
                            + content + "\".", matcher.start());
                values.add(content);
            } else {
                throw new ParseException("Illegal route rule \"" + rule
                        + "\", The error char '" + separator + "' at index "
                        + matcher.start() + " before \"" + content + "\".", matcher.start());
            }
        }
        return condition;
    }

    public static void main(String[] args) {

        try {
            Map<String, MatchPair> matchPairMap = parseRule("host = 10.20.153.10,10.20.153.11 & application = mamba & method = find*,list*,get*,is*");
            System.out.println();
        } catch (ParseException e) {
            e.printStackTrace();
        }

    }

    /**
     * 该方法是进行路由规则的匹配，分别对消费者和提供者进行匹配。
     *
     * @param invokers 提供者Invoker
     * @param url      消费者 url
     */
    @Override
    public <T> List<Invoker<T>> route(List<Invoker<T>> invokers, URL url, Invocation invocation)
            throws RpcException {
        // 为空，直接返回空 Invoker 集合
        if (invokers == null || invokers.isEmpty()) {
            return invokers;
        }
        try {
            // 判断消费者与路由规则是否匹配
            // 如果不匹配 `whenCondition` ，直接返回 `invokers` 集合，因为不需要走 `thenCondition` 的匹配
            // 意味着 如果whenCondition为空，那么适用于所有消费者，即matchWhen方法返回true。
            if (!matchWhen(url, invocation)) {
                return invokers;
            }
            List<Invoker<T>> result = new ArrayList<Invoker<T>>();
            // 如果thenCondition为空，则直接返回空
            if (thenCondition == null) {
                logger.warn("The current consumer in the service blacklist. consumer: " + NetUtils.getLocalHost() + ", service: " + url.getServiceKey());
                return result;
            }
            // 遍历invokers
            for (Invoker<T> invoker : invokers) {
                // 如果thenCondition匹配，则加入result
                // 意味着 如果thenCondition为空，那么禁止所有提供者，即matchWhen方法返回false。
                if (matchThen(invoker.getUrl(), url)) {
                    result.add(invoker);
                }
            }
            if (!result.isEmpty()) {
                return result;
            } else if (force) {
                logger.warn("The route result is empty and force execute. consumer: " + NetUtils.getLocalHost() + ", service: " + url.getServiceKey() + ", router: " + url.getParameterAndDecoded(Constants.RULE_KEY));
                return result;
            }
        } catch (Throwable t) {
            logger.error("Failed to execute condition router rule: " + getUrl() + ", invokers: " + invokers + ", cause: " + t.getMessage(), t);
        }
        return invokers;
    }

    @Override
    public URL getUrl() {
        return url;
    }

    @Override
    public int compareTo(Router o) {
        if (o == null || o.getClass() != ConditionRouter.class) {
            return 1;
        }
        ConditionRouter c = (ConditionRouter) o;
        return this.priority == c.priority ? url.toFullString().compareTo(c.url.toFullString()) : (this.priority > c.priority ? 1 : -1);
    }

    boolean matchWhen(URL url, Invocation invocation) {
        return whenCondition == null || whenCondition.isEmpty() || matchCondition(whenCondition, url, null, invocation);
    }

    private boolean matchThen(URL url, URL param) {
        return !(thenCondition == null || thenCondition.isEmpty()) && matchCondition(thenCondition, url, param, null);
    }

    /**
     * host = 10.20.153.10,10.20.153.11 & application = mamba
     * 例如condition为
     * key1:host value:MatchPair实例，其中matches大小为2，分别为10.20.153.10,10.20.153.11； mismatches为空
     * key2:application value:MatchPair实例，其中matches大小为1，为mamba； mismatches为空
     */
    private boolean matchCondition(Map<String, MatchPair> condition, URL url, URL param, Invocation invocation) {
        Map<String, String> sample = url.toMap();
        // 是否匹配
        boolean result = false;
        // 遍历条件
        for (Map.Entry<String, MatchPair> matchPair : condition.entrySet()) {
            String key = matchPair.getKey();
            String sampleValue;
            //get real invoked method name from invocation
            // 获得方法名
            if (invocation != null && (Constants.METHOD_KEY.equals(key) || Constants.METHODS_KEY.equals(key))) {
                sampleValue = invocation.getMethodName();
            } else {
                sampleValue = sample.get(key);
                if (sampleValue == null) {
                    sampleValue = sample.get(Constants.DEFAULT_KEY_PREFIX + key);
                }
            }
            if (sampleValue != null) {
                // 如果不匹配条件值，返回false
                if (!matchPair.getValue().isMatch(sampleValue, param)) {
                    return false;
                } else {
                    // 匹配则返回true
                    result = true;
                }
            } else {
                //not pass the condition
                if (!matchPair.getValue().matches.isEmpty()) {
                    // 如果匹配的集合不为空返回false
                    return false;
                } else {
                    // 返回返回true
                    result = true;
                }
            }
        }
        return result;
    }

    private static final class MatchPair {

        /**
         * 匹配的值的集合
         */
        final Set<String> matches = new HashSet<String>();

        /**
         * 不匹配的值的集合
         */
        final Set<String> mismatches = new HashSet<String>();

        /**
         * 判断value是否匹配matches或者mismatches
         */
        private boolean isMatch(String value, URL param) {

            // 只匹配 matches
            if (!matches.isEmpty() && mismatches.isEmpty()) {
                for (String match : matches) {
                    if (UrlUtils.isMatchGlobPattern(match, value, param)) {
                        // 匹配上了返回true
                        return true;
                    }
                }
                // 未匹配上了返回true
                return false;
            }

            // 只匹配 mismatches
            if (!mismatches.isEmpty() && matches.isEmpty()) {
                for (String mismatch : mismatches) {
                    if (UrlUtils.isMatchGlobPattern(mismatch, value, param)) {
                        // 匹配上了返回false
                        return false;
                    }
                }
                // 没匹配上，则为true
                return true;
            }

            // 匹配 matches和mismatches
            if (!matches.isEmpty() && !mismatches.isEmpty()) {
                //when both mismatches and matches contain the same value, then using mismatches first
                for (String mismatch : mismatches) {
                    if (UrlUtils.isMatchGlobPattern(mismatch, value, param)) {
                        // 匹配上则为false
                        return false;
                    }
                }
                for (String match : matches) {
                    if (UrlUtils.isMatchGlobPattern(match, value, param)) {
                        // 匹配上则为true
                        return true;
                    }
                }
                return false;
            }
            return false;
        }
    }
}

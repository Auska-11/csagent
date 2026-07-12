package com.smartcs.smart_cs_agent.workflow.userprofile;

import com.smartcs.smart_cs_agent.agent.action.AskUserAction;
import com.smartcs.smart_cs_agent.agent.action.CapabilityAction;
import com.smartcs.smart_cs_agent.agent.action.FinalAnswerAction;
import com.smartcs.smart_cs_agent.agent.action.HandoffAction;
import com.smartcs.smart_cs_agent.agent.context.CapabilityObservation;
import com.smartcs.smart_cs_agent.agent.context.Observation;
import com.smartcs.smart_cs_agent.agent.context.TurnContext;
import com.smartcs.smart_cs_agent.capability.api.CapabilityIds;
import com.smartcs.smart_cs_agent.capability.api.contracts.UserProfileSummary;
import com.smartcs.smart_cs_agent.memory.UserMemorySnapshot;
import com.smartcs.smart_cs_agent.memory.UserMemoryStore;
import com.smartcs.smart_cs_agent.skill.CustomerServiceIntentRules;
import com.smartcs.smart_cs_agent.skill.CustomerServiceSlotExtractor;
import com.smartcs.smart_cs_agent.workflow.api.AgentWorkflow;
import com.smartcs.smart_cs_agent.workflow.api.RoutingContext;
import com.smartcs.smart_cs_agent.workflow.api.WorkflowDecision;
import com.smartcs.smart_cs_agent.workflow.api.WorkflowDescriptor;
import com.smartcs.smart_cs_agent.workflow.api.WorkflowMatchResult;
import com.smartcs.smart_cs_agent.workflow.core.GlobalInterruptDecision;
import com.smartcs.smart_cs_agent.workflow.core.GlobalInterruptPolicy;
import com.smartcs.smart_cs_agent.workflow.core.GlobalInterruptType;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 当前用户资料与长期商品偏好的独立 Workflow。
 *
 * <p>资料查询通过 user.lookup 获取；偏好读写通过 UserMemoryStore。二者都要求服务端认证身份，
 * 不能只相信请求中的 userId 字符串。</p>
 */
@Component
public class UserProfileWorkflow implements AgentWorkflow {

    public static final String ID = UserProfileState.WORKFLOW_ID;
    public static final String VERSION = "1.0.0";

    private final WorkflowDescriptor descriptor = new WorkflowDescriptor(
            ID,
            VERSION,
            "查询当前用户资料，并管理长期商品偏好",
            168
    );

    private final CustomerServiceIntentRules intentRules = new CustomerServiceIntentRules();
    private final CustomerServiceSlotExtractor slotExtractor = new CustomerServiceSlotExtractor();
    private final GlobalInterruptPolicy interruptPolicy;
    private final UserMemoryStore userMemoryStore;

    public UserProfileWorkflow(GlobalInterruptPolicy interruptPolicy,
                               UserMemoryStore userMemoryStore) {
        this.interruptPolicy = interruptPolicy;
        this.userMemoryStore = userMemoryStore;
    }

    @Override
    public WorkflowDescriptor descriptor() {
        return descriptor;
    }

    @Override
    public WorkflowMatchResult match(RoutingContext context) {
        String message = normalize(context.getMessage());
        if (isPreferenceReadMessage(message)) {
            return new WorkflowMatchResult(0.99, "命中长期商品偏好查看表达");
        }
        if (intentRules.isPreferenceMemoryMessage(message)) {
            return new WorkflowMatchResult(0.99, "命中长期商品偏好写入表达");
        }
        if (containsAny(message, "订单", "物流", "退货", "退款", "换货")) {
            return WorkflowMatchResult.noMatch("订单或退换货任务不属于用户资料查询");
        }
        if (isProfileQuery(message)) {
            return new WorkflowMatchResult(0.98, "命中当前用户资料查询表达");
        }
        return WorkflowMatchResult.noMatch("未命中用户资料或偏好表达");
    }

    @Override
    public WorkflowDecision nextAction(TurnContext context) {
        String message = normalize(context.getMessage());
        UserProfileState state = new UserProfileState(context.getState());
        boolean preferenceRead = isPreferenceReadMessage(message);
        boolean preferenceWrite = state.getStep() == UserProfileStep.WAITING_PREFERENCE
                || (!preferenceRead && intentRules.isPreferenceMemoryMessage(message));
        context.getState().setActiveIntent(
                preferenceWrite ? "user_memory_update" : "user_info"
        );

        WorkflowDecision interrupted = handleInterrupt(context, state);
        if (interrupted != null) {
            return interrupted;
        }

        if (!context.getRequest().getPrincipal().isAuthenticated()) {
            state.transitionTo(UserProfileStep.CANCELLED);
            return WorkflowDecision.next(
                    new FinalAnswerAction("查看或修改个人资料前需要先登录并验证身份。"),
                    "用户资料 Workflow 拒绝未认证身份"
            );
        }

        return switch (state.getStep()) {
            case START -> start(context, state);
            case WAITING_PREFERENCE -> collectPreference(context, state);
            case LOOKING_UP_PROFILE -> handleProfileResult(context, state);
            case COMPLETED -> WorkflowDecision.next(
                    new FinalAnswerAction("本次用户资料处理已经完成。"),
                    "用户资料 Workflow 已完成"
            );
            case CANCELLED -> WorkflowDecision.next(
                    new FinalAnswerAction("本次用户资料处理已取消。"),
                    "用户资料 Workflow 已取消"
            );
            case FAILED -> technicalFailure("用户资料 Workflow 已失败");
        };
    }

    private WorkflowDecision start(TurnContext context, UserProfileState state) {
        String message = normalize(context.getMessage());
        if (isPreferenceReadMessage(message)) {
            return showPreferences(context, state);
        }
        if (intentRules.isPreferenceMemoryMessage(message)) {
            String preference = slotExtractor.extractProductPreference(context.getMessage());
            if (!slotExtractor.hasText(preference)) {
                state.transitionTo(UserProfileStep.WAITING_PREFERENCE);
                return WorkflowDecision.next(
                        new AskUserAction("请告诉我具体喜欢的商品或品类，例如蓝牙耳机或智能手表。"),
                        "偏好写入缺少具体商品"
                );
            }
            return rememberPreference(context, state, preference);
        }
        state.transitionTo(UserProfileStep.LOOKING_UP_PROFILE);
        return WorkflowDecision.next(
                new CapabilityAction(CapabilityIds.USER_LOOKUP, Map.of()),
                "查询服务端认证 Principal 对应的用户资料"
        );
    }

    private WorkflowDecision collectPreference(TurnContext context, UserProfileState state) {
        String preference = slotExtractor.extractProductPreference(context.getMessage());
        if (!slotExtractor.hasText(preference)) {
            return WorkflowDecision.next(
                    new AskUserAction("我还没有识别到具体商品偏好，请提供一个商品或品类名称。"),
                    "仍未取得长期商品偏好"
            );
        }
        return rememberPreference(context, state, preference);
    }

    private WorkflowDecision rememberPreference(TurnContext context,
                                                UserProfileState state,
                                                String preference) {
        String userId = context.getRequest().getPrincipal().getUserId();
        boolean added = userMemoryStore.rememberProductPreference(userId, preference);
        UserMemorySnapshot snapshot = userMemoryStore.getSnapshot(userId);
        state.setPreference(preference);
        state.transitionTo(UserProfileStep.COMPLETED);

        Map<String, Object> traceData = new LinkedHashMap<>();
        traceData.put("userId", userId);
        traceData.put("preference", preference);
        traceData.put("added", added);
        traceData.put("allProductPreferences", snapshot.getProductPreferences());
        context.addTrace("user_memory_updated", "记录认证用户长期商品偏好", traceData);

        String answer = added
                ? "好的，我记住了：您比较喜欢“" + preference + "”。之后推荐商品时会优先参考这个偏好。"
                : "这个偏好已经记录过：您比较喜欢“" + preference + "”。";
        return WorkflowDecision.next(new FinalAnswerAction(answer), "长期商品偏好已安全写入");
    }

    private WorkflowDecision showPreferences(TurnContext context, UserProfileState state) {
        String userId = context.getRequest().getPrincipal().getUserId();
        UserMemorySnapshot snapshot = userMemoryStore.getSnapshot(userId);
        state.transitionTo(UserProfileStep.COMPLETED);
        if (!snapshot.hasProductPreferences()) {
            return WorkflowDecision.next(
                    new FinalAnswerAction("当前账号还没有保存商品偏好。"),
                    "认证用户没有长期商品偏好"
            );
        }

        List<String> preferences = snapshot.getProductPreferences();
        return WorkflowDecision.next(
                new FinalAnswerAction("当前已保存的商品偏好：" + String.join("、", preferences) + "。"),
                "返回认证用户的长期商品偏好快照"
        );
    }

    private WorkflowDecision handleProfileResult(TurnContext context, UserProfileState state) {
        CapabilityObservation result = latestCapabilityResult(context);
        if (result == null || !CapabilityIds.USER_LOOKUP.equals(result.getCapabilityId())) {
            state.transitionTo(UserProfileStep.FAILED);
            return technicalFailure("没有获得预期的 user.lookup 结果");
        }
        if (result.isBusinessRejected() && "USER_NOT_FOUND".equals(result.getErrorCode())) {
            state.transitionTo(UserProfileStep.COMPLETED);
            return WorkflowDecision.next(
                    new FinalAnswerAction("当前登录账号没有找到可用的用户资料，请联系人工客服核对。"),
                    "认证用户资料不存在"
            );
        }
        if (!result.isSuccess() || !(result.getData() instanceof UserProfileSummary profile)) {
            state.transitionTo(UserProfileStep.FAILED);
            return technicalFailure("用户资料能力技术失败或输出契约不正确");
        }

        state.transitionTo(UserProfileStep.COMPLETED);
        String answer = "当前账号资料：\n"
                + "- 用户 ID：" + safeText(profile.getUserId(), "未知") + "\n"
                + "- 姓名：" + safeText(profile.getName(), "未填写") + "\n"
                + "- 手机号：" + safeText(profile.getPhoneMasked(), "未填写");
        return WorkflowDecision.next(
                new FinalAnswerAction(answer),
                "使用脱敏 UserProfileSummary 返回当前认证用户资料"
        );
    }

    private WorkflowDecision handleInterrupt(TurnContext context, UserProfileState state) {
        GlobalInterruptDecision interrupt = interruptPolicy.evaluate(context.getMessage());
        if (interrupt.getType() == GlobalInterruptType.CANCEL) {
            state.transitionTo(UserProfileStep.CANCELLED);
            return WorkflowDecision.next(
                    new FinalAnswerAction("本次用户资料处理已取消。"),
                    interrupt.getReason()
            );
        }
        if (interrupt.getType() == GlobalInterruptType.HANDOFF) {
            Map<String, Object> handoffContext = new LinkedHashMap<>(state.snapshot());
            state.transitionTo(UserProfileStep.CANCELLED);
            return WorkflowDecision.next(
                    new HandoffAction(
                            "USER_REQUESTED_HUMAN",
                            "用户在资料或偏好处理中要求人工客服",
                            handoffContext
                    ),
                    interrupt.getReason()
            );
        }
        return null;
    }

    private WorkflowDecision technicalFailure(String reason) {
        return WorkflowDecision.next(
                new FinalAnswerAction("用户资料服务暂时无法完成处理，请稍后重试或联系人工客服。"),
                reason
        );
    }

    private CapabilityObservation latestCapabilityResult(TurnContext context) {
        Observation observation = context.getLatestObservationByType("capability_result");
        return observation != null && observation.getData() instanceof CapabilityObservation result
                ? result
                : null;
    }

    private boolean isProfileQuery(String message) {
        return containsAny(
                message,
                "my info", "my profile", "account", "我的信息", "我的资料", "个人信息", "账号信息",
                "查看资料", "我是谁", "我的手机号", "会员信息"
        );
    }

    private boolean isPreferenceReadMessage(String message) {
        return containsAny(
                message,
                "我的偏好", "我喜欢什么", "记得我喜欢", "保存了什么偏好", "查看偏好",
                "my preferences"
        );
    }

    private String safeText(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private boolean containsAny(String text, String... keywords) {
        for (String keyword : keywords) {
            if (text.contains(keyword.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }
}

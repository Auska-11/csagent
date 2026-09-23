package com.smartcs.smart_cs_agent.channel.xianyu;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 已售守卫的意图矩阵：购买/议价/成交确认必须拦截，售后求助、商品咨询、闲聊
 * 与放弃信号必须放行——不得对所有已售会话消息一律回已售。
 */
class XianyuSoldItemGuardTest {

    @Test
    void purchaseAndDealConfirmationIntentsAdvanceTheDeal() {
        assertTrue(XianyuSoldItemGuard.isDealAdvancingIntent("还在吗"));
        assertTrue(XianyuSoldItemGuard.isDealAdvancingIntent("这个还有吗"));
        assertTrue(XianyuSoldItemGuard.isDealAdvancingIntent("多少钱"));
        assertTrue(XianyuSoldItemGuard.isDealAdvancingIntent("这个怎么卖"));
        assertTrue(XianyuSoldItemGuard.isDealAdvancingIntent("250卖吗"));
        assertTrue(XianyuSoldItemGuard.isDealAdvancingIntent("能便宜点吗"));
        assertTrue(XianyuSoldItemGuard.isDealAdvancingIntent("250可以成交"));
        assertTrue(XianyuSoldItemGuard.isDealAdvancingIntent("好的，我拍了"));
        assertTrue(XianyuSoldItemGuard.isDealAdvancingIntent("240"));
        assertTrue(XianyuSoldItemGuard.isDealAdvancingIntent("要了"));
    }

    @Test
    void afterSaleAndProductQuestionIntentsDoNotAdvanceTheDeal() {
        // 用户点名的售后求助：已售不是挡箭牌，照常协助排查。
        assertFalse(XianyuSoldItemGuard.isDealAdvancingIntent("耳机跟我手机连不上蓝牙怎么办"));
        assertFalse(XianyuSoldItemGuard.isDealAdvancingIntent("我买的这个蓝牙坏了怎么弄"));
        assertFalse(XianyuSoldItemGuard.isDealAdvancingIntent("音量调到最低还是没声音"));
        // 客观商品咨询（不含购买/价格词）。
        assertFalse(XianyuSoldItemGuard.isDealAdvancingIntent("这个支持蓝牙吗"));
        assertFalse(XianyuSoldItemGuard.isDealAdvancingIntent("有什么瑕疵"));
        assertFalse(XianyuSoldItemGuard.isDealAdvancingIntent("是这个型号吗"));
        // 闲聊与放弃。
        assertFalse(XianyuSoldItemGuard.isDealAdvancingIntent("颜值真高"));
        assertFalse(XianyuSoldItemGuard.isDealAdvancingIntent("你好"));
        assertFalse(XianyuSoldItemGuard.isDealAdvancingIntent("那我不买了，谢谢"));
        assertFalse(XianyuSoldItemGuard.isDealAdvancingIntent("我不要了"));
        // 边界句：聊支付经历/型号价格认知，不是出价。
        assertFalse(XianyuSoldItemGuard.isDealAdvancingIntent("我昨天用支付宝付的钱"));
        assertFalse(XianyuSoldItemGuard.isDealAdvancingIntent("官网卖2000的东西你才挂265？"));
    }

    @Test
    void explicitDealIntentOverridesPolicyAndFaultWords() {
        // 六轮评审点名的两个漏网句：售后/政策词不得覆盖明确的问价或购买意图。
        assertTrue(XianyuSoldItemGuard.isDealAdvancingIntent("有保修吗？多少钱"));
        assertTrue(XianyuSoldItemGuard.isDealAdvancingIntent("好漂亮啊，没有问题我就要了"));
        // 同族边界：政策词与交易信号并存时按交易处理。
        assertTrue(XianyuSoldItemGuard.isDealAdvancingIntent("有问题吗？多少钱"));
        assertTrue(XianyuSoldItemGuard.isDealAdvancingIntent("这耳机维修过吗？还在吗"));
        assertTrue(XianyuSoldItemGuard.isDealAdvancingIntent("没有问题的话今天就拍下"));
        assertTrue(XianyuSoldItemGuard.isDealAdvancingIntent("没问题的，怎么买"));
    }

    @Test
    void remediationCostQuestionIsNotItemPriceInquiry() {
        // 七轮评审复现：“我收到的耳机坏了，维修多少钱”——已购商品的维修费用咨询，
        // 问价对象是维修不是商品，不得拿售出状态挡回。
        assertFalse(XianyuSoldItemGuard.isDealAdvancingIntent("我收到的耳机坏了，维修多少钱"));
        assertFalse(XianyuSoldItemGuard.isDealAdvancingIntent("我收到的耳机坏了，修一下要多少钱"));
        assertFalse(XianyuSoldItemGuard.isDealAdvancingIntent("收到的东西有杂音，换货要多少钱"));
        assertFalse(XianyuSoldItemGuard.isDealAdvancingIntent("寄回运费多少钱"));
        assertFalse(XianyuSoldItemGuard.isDealAdvancingIntent("多少钱能修好"));
        // 成对边界：售前组合不受影响——问价从句独立、或问的是维修史/政策，照常拦截。
        assertTrue(XianyuSoldItemGuard.isDealAdvancingIntent("有保修吗？多少钱"));
        assertTrue(XianyuSoldItemGuard.isDealAdvancingIntent("有问题吗？多少钱"));
        assertTrue(XianyuSoldItemGuard.isDealAdvancingIntent("这耳机维修过吗？还在吗"));
        assertTrue(XianyuSoldItemGuard.isDealAdvancingIntent("能修吗？多少钱"));
        // 报障之外另买一件：是新交易推进，不是维修计价，仍拦截。
        assertTrue(XianyuSoldItemGuard.isDealAdvancingIntent("我收到的耳机坏了，再买一个多少钱"));
    }

    @Test
    void repairHistoryAndIndependentPurchaseSurviveRemediationStrip() {
        // 八轮评审复现：服务计价剥离曾把“维修”到“多少钱”之间的任意文字整段吞掉。
        // 维修历史（“过”）、定语（“的”）、商品名、购买意图都不允许被吞。
        assertTrue(XianyuSoldItemGuard.isDealAdvancingIntent("维修过的这款耳机多少钱"));
        assertTrue(XianyuSoldItemGuard.isDealAdvancingIntent("维修过也行我想买这件多少钱"));
        assertTrue(XianyuSoldItemGuard.isDealAdvancingIntent("修过的这个多少钱"));
        assertTrue(XianyuSoldItemGuard.isDealAdvancingIntent("修好的耳机多少钱"));
        // 剥离只删计价表达本身：同句的独立购买信号照常生效。
        assertTrue(XianyuSoldItemGuard.isDealAdvancingIntent("维修多少钱？这件还在吗"));
        // 收紧后明确的服务计价表达仍然放行。
        assertFalse(XianyuSoldItemGuard.isDealAdvancingIntent("如果要维修的话多少钱"));
        assertFalse(XianyuSoldItemGuard.isDealAdvancingIntent("维修费用是多少"));
        assertFalse(XianyuSoldItemGuard.isDealAdvancingIntent("维修收多少钱"));
        assertFalse(XianyuSoldItemGuard.isDealAdvancingIntent("换一个多少钱"));
        assertFalse(XianyuSoldItemGuard.isDealAdvancingIntent("多少钱能修一下"));
    }

    @Test
    void declineRefundAndAfterSaleWithoutDealSignalStillDoNotAdvance() {
        // 明确放弃与退货退款保持否决：交易的反方向。
        assertFalse(XianyuSoldItemGuard.isDealAdvancingIntent("那我不买了，谢谢"));
        assertFalse(XianyuSoldItemGuard.isDealAdvancingIntent("我不要了"));
        assertFalse(XianyuSoldItemGuard.isDealAdvancingIntent("我付了钱想退款"));
        assertFalse(XianyuSoldItemGuard.isDealAdvancingIntent("收货后发现有问题，怎么退货"));
        // 政策/售后词去掉否决权后，靠“无交易信号默认放行”维持原判。
        assertFalse(XianyuSoldItemGuard.isDealAdvancingIntent("我买的这个蓝牙坏了怎么弄"));
        assertFalse(XianyuSoldItemGuard.isDealAdvancingIntent("耳机跟我手机连不上蓝牙怎么办"));
        assertFalse(XianyuSoldItemGuard.isDealAdvancingIntent("音量调到最低还是没声音"));
        assertFalse(XianyuSoldItemGuard.isDealAdvancingIntent("这耳机保修多久"));
    }

    @Test
    void isSoldOutRequiresKnownZeroStock() {
        assertTrue(XianyuSoldItemGuard.isSoldOut(facts(0)));
        assertFalse(XianyuSoldItemGuard.isSoldOut(facts(1)));
        assertFalse(XianyuSoldItemGuard.isSoldOut(facts(-1)));
        assertFalse(XianyuSoldItemGuard.isSoldOut(null));
    }

    private XianyuItemFactService.ItemFacts facts(int stock) {
        return new XianyuItemFactService.ItemFacts(
                "1079979582985",
                "森海塞尔MOMENTUM二代头戴耳机",
                XianyuItemFactService.PriceRange.of(new java.math.BigDecimal("265")),
                stock,
                "森海塞尔 MOMENTUM 2 代头戴式蓝牙耳机。",
                "功能未测试；使用痕迹"
        );
    }
}

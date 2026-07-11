package com.smartcs.smart_cs_agent.skill.registry;

import com.smartcs.smart_cs_agent.capability.api.CapabilityCatalog;
import com.smartcs.smart_cs_agent.capability.api.CapabilityGateway;
import com.smartcs.smart_cs_agent.skill.api.AgentSkillDefinition;
import com.smartcs.smart_cs_agent.skill.api.AgentSkillLoader;
import com.smartcs.smart_cs_agent.skill.api.AgentSkillPackage;
import com.smartcs.smart_cs_agent.skill.loader.AgentSkillLoadException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 经过校验的 Agent Skill Package 不可变注册表。
 *
 * <p>启动时一次性完成以下检查：</p>
 * <ol>
 *     <li>Skill ID 不能重复；</li>
 *     <li>同一个 Workflow 不能同时绑定两份含义不清的 Package；</li>
 *     <li>allowed_capabilities 中的每个 ID 都必须存在于 CapabilityCatalog。</li>
 * </ol>
 *
 * <p>文件存在性、版本格式等由 Parser 更早校验。采用“启动失败”而不是运行中降级，
 * 是因为错误授权或缺失政策文件属于部署配置错误，静默忽略会产生更难发现的业务风险。</p>
 */
@Component
public class AgentSkillPackageRegistry {

    private static final Logger log = LoggerFactory.getLogger(AgentSkillPackageRegistry.class);

    private final Map<String, AgentSkillPackage> byId;
    private final Map<String, AgentSkillPackage> byWorkflowId;

    @Autowired
    public AgentSkillPackageRegistry(AgentSkillLoader loader, CapabilityGateway capabilityGateway) {
        this(loader.load(), capabilityGateway.catalog());
    }

    /**
     * 测试和纯 Java 场景可直接传入 Package 与 Catalog，不必搭建 Spring 容器。
     */
    public AgentSkillPackageRegistry(Collection<AgentSkillPackage> packages,
                                     CapabilityCatalog capabilityCatalog) {
        Map<String, AgentSkillPackage> idIndex = new LinkedHashMap<>();
        Map<String, AgentSkillPackage> workflowIndex = new LinkedHashMap<>();

        if (packages != null) {
            for (AgentSkillPackage skillPackage : packages) {
                AgentSkillDefinition definition = skillPackage.getDefinition();
                validateCapabilities(definition, capabilityCatalog);

                AgentSkillPackage duplicateId = idIndex.putIfAbsent(definition.getId(), skillPackage);
                if (duplicateId != null) {
                    throw new AgentSkillLoadException("Duplicate Skill id: " + definition.getId());
                }

                AgentSkillPackage duplicateWorkflow = workflowIndex.putIfAbsent(
                        definition.getWorkflowId(),
                        skillPackage
                );
                if (duplicateWorkflow != null) {
                    throw new AgentSkillLoadException(
                            "Workflow '" + definition.getWorkflowId() + "' is bound by multiple Skill packages: "
                                    + duplicateWorkflow.getDefinition().getId() + ", " + definition.getId()
                    );
                }

                log.info(
                        "Loaded Agent Skill package id={}, version={}, workflow={}, source={}",
                        definition.getId(),
                        definition.getVersion(),
                        definition.getWorkflowId(),
                        skillPackage.getSourceDescription()
                );
            }
        }

        this.byId = Collections.unmodifiableMap(idIndex);
        this.byWorkflowId = Collections.unmodifiableMap(workflowIndex);
    }

    public Optional<AgentSkillPackage> findById(String skillId) {
        return Optional.ofNullable(byId.get(skillId));
    }

    public Optional<AgentSkillPackage> findByWorkflowId(String workflowId) {
        return Optional.ofNullable(byWorkflowId.get(workflowId));
    }

    public List<AgentSkillPackage> list() {
        return List.copyOf(byId.values());
    }

    private void validateCapabilities(AgentSkillDefinition definition, CapabilityCatalog catalog) {
        if (catalog == null) {
            throw new AgentSkillLoadException("CapabilityCatalog cannot be null while validating Skill packages");
        }
        for (String capabilityId : definition.getAllowedCapabilities()) {
            if (!catalog.contains(capabilityId)) {
                throw new AgentSkillLoadException(
                        "Skill '" + definition.getId() + "' references unknown capability: " + capabilityId
                );
            }
        }
    }
}

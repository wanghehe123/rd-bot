// 首发自托管 onboarding 合同（openspec/changes/prepare-personal-open-source-release T08）。
// 纯函数：输入已加载的 provider/project 快照，输出 checklist 与阻断原因；
// 不发请求、不读 localStorage，供 Dashboard 卡片与任务创建对话框复用。

export type OnboardingProviderSnapshot = {
  enabled: boolean;
  credentialConfigured: boolean;
};

export type OnboardingProjectSnapshot = {
  enabled?: boolean;
  repositoryUrl?: string | null;
};

export type OnboardingStep = {
  id: "provider" | "project" | "repository" | "requirement";
  done: boolean;
  label: string;
  description: string;
  href: string;
};

/** 是否存在「已启用且已配置凭据」的模型供应商。 */
export function hasUsableProvider(providers: OnboardingProviderSnapshot[]): boolean {
  return providers.some((item) => item.enabled && item.credentialConfigured);
}

/** 无可用 provider 时创建任务前的明确阻断原因；null 表示可以继续。 */
export function providerConfigBlockReason(providers: OnboardingProviderSnapshot[]): string | null {
  if (hasUsableProvider(providers)) {
    return null;
  }
  if (providers.length === 0) {
    return "尚未配置模型供应商：请先在「模型供应商」页面注册供应商并保存 API Key";
  }
  return "模型供应商缺少 API Key 或未启用：请到「模型供应商」页面完成凭据配置";
}

/** 四步首发 checklist：配置供应商 → 创建/选择项目 → 配置授权仓库 → 提交需求。 */
export function onboardingChecklist(input: {
  providers: OnboardingProviderSnapshot[];
  projects: OnboardingProjectSnapshot[];
}): OnboardingStep[] {
  const providerReady = hasUsableProvider(input.providers);
  const anyProject = input.projects.length > 0;
  const authorizedProject = input.projects.some(
    (item) => item.enabled !== false && Boolean((item.repositoryUrl || "").trim())
  );

  return [
    {
      id: "provider",
      done: providerReady,
      label: "配置模型供应商",
      description: "注册供应商并保存 API Key（凭据只提交到后端安全存储）",
      href: "/admin/model-providers"
    },
    {
      id: "project",
      done: anyProject,
      label: "创建或选择项目",
      description: "在项目列表中新建一个交付项目",
      href: "/admin/projects"
    },
    {
      id: "repository",
      done: authorizedProject,
      label: "配置授权仓库",
      description: "为项目填写你授权 RD-Bot 操作的 GitHub 仓库地址",
      href: "/admin/projects"
    },
    {
      id: "requirement",
      done: false,
      label: "提交第一条需求",
      description: "在任务管理中新建需求任务并提交执行",
      href: "/admin/rd-tasks"
    }
  ];
}

/** checklist 前三步全部完成时才认为引导完成（第四步由用户实际提交驱动）。 */
export function onboardingComplete(steps: OnboardingStep[]): boolean {
  return steps.filter((step) => step.id !== "requirement").every((step) => step.done);
}

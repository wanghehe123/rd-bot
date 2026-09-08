# Third-Party Notices

RD-Bot 以 [MIT 许可证](LICENSE) 发布。本说明只界定项目自有代码与第三方素材的边界：MIT 许可证
只覆盖 RD-Bot 项目自有代码，不会改写、覆盖或替代任何第三方组件、基础镜像、字体、图标或截图素材的
原始许可证。各第三方组件的许可条款以其自身发布为准。

## 仓库内纳入的第三方代码

| 内容 | 位置 | 许可证 | 说明 |
| --- | --- | --- | --- |
| Maven Wrapper 脚本 | `mvnw`, `mvnw.cmd`, `.mvn/wrapper/` | Apache License 2.0 | Apache Maven Wrapper 发行物，保留其原始许可声明。 |

## 依赖组件

- **Maven 依赖**：由根 `pom.xml` 与各模块 POM 声明，传递闭包中各组件沿用其自身许可证
  （如 Spring Boot/Apache-2.0、PostgreSQL JDBC/BSD-2-Clause 等）。可用
  `./mvnw -q dependency:tree` 或 `help:effective-pom` 复核。
- **前端 npm 依赖**：见 `frontend/package-lock.json`，各包沿用其自身许可证。
- **Pi bridge npm 依赖**：见 `bootstrap/src/main/resources/executor/pi/package-lock.json`
  （含 `@earendil-works/pi-coding-agent`），各包沿用其自身许可证。

lockfile 与依赖元数据中出现的许可证字段属于第三方声明，不是对 RD-Bot 项目整体的许可证变更。

## 容器基础镜像

Docker 镜像构建（根 `Dockerfile` 与 `bootstrap/src/main/resources/executor/pi/Dockerfile*`）使用的
基础镜像（Node.js、Maven、Eclipse Temurin、pgvector/pg16、Redis、MinIO 等）由其各自上游以相应许可证
（GPL-2.0、Apache-2.0、MIT、BSD 等）发布，随镜像分发的上游软件沿用其原始许可。

## README 与文档素材

`assets/readme/` 下用于 README 展示的图片与动画的来源、生成方式与第三方素材使用情况，
记录在 [`assets/readme/ASSET_PROVENANCE.md`](assets/readme/ASSET_PROVENANCE.md)。
项目 README 的结构与措辞为 RD-Bot 自有内容；仅参考了公开仓库的信息组织方式，未复制其文案、品牌或素材。

## 商标与品牌

文中提及的第三方项目、产品与服务名称是其各自所有者的商标，仅用于说明互操作或来源，不构成背书。

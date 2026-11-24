# 参与 SmartEval

欢迎修复缺陷、改进文档和提交新功能。问题报告请包含复现步骤、相关版本和已脱敏的日志。示例答卷请自行构造。

## 开发环境

- Business 使用 JDK 21 和 Maven，默认测试数据库是 H2 的 MySQL 兼容模式。
- Intelligence 部署镜像使用 Python 3.12。MCP 使用独立的 Python 3.11+ 虚拟环境。
- Web 使用 Node.js 24 和 npm。完整部署使用 Docker Compose。

在仓库根目录运行以下命令。分别在 Intelligence 和 MCP 的环境中安装对应依赖。

~~~bash
mvn -f services/business/pom.xml test

python -m pip install -r services/intelligence/requirements.txt
cd services/intelligence
python -m unittest discover -s tests -v
cd ../..

python -m pip install ./services/mcp
python -m unittest discover -s services/mcp/tests -v

npm --prefix apps/web ci
npm --prefix apps/web run build

python -m pip install openapi-spec-validator
python -m openapi_spec_validator contracts/openapi/grading-v2.yaml
~~~

可设置 `SMARTEVAL_TEST_MYSQL_URL`、`SMARTEVAL_TEST_MYSQL_USER` 和 `SMARTEVAL_TEST_MYSQL_PASSWORD`，用新的隔离测试库再运行 Maven 测试。测试会写入数据。

## 提交变更

1. 公共 HTTP 路由、认证范围或响应字段变更时，同步更新 [OpenAPI 3.1](contracts/openapi/grading-v2.yaml)。Java 路由一致性测试会比较 Spring 实际注册的全部操作。新增响应字段应使用真实响应验证。
2. 数据库变更添加新的 Flyway migration。验证空库部署以及从前一版本更新的路径。
3. 改动作业、判分或证据映射时，补充能复现问题的回归样本，并在 PR 中记录执行命令、结果及使用的外部服务或测试替身。
4. 提交前运行 `git diff --check`。提交内容应省略密钥、私有答卷、本地数据目录和构建产物。

建议用小范围的 PR 说明改动和验证方式。项目采用 [Apache-2.0](LICENSE)。

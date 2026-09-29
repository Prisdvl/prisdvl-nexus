# ============================================================
# Elasticsearch + analysis-ik（中文分词）
#
# 为什么需要自定义镜像：
#   analysis-ik 的版本必须与 ES 版本**严格一致**（每个 ES 版本对应一个独立插件包，
#   不是范围兼容）。把它固化进镜像，才能保证 `docker compose up` 出来的环境可复现，
#   而不是"手工进容器装插件"（换机器就忘了）。
#
# 为什么不用官方 analysis-smartcn：
#   IK 的中文切分质量与词典生态更适合中文技术笔记；smartcn 作为备选（见 decisions.md）。
#
# 插件发布地址注意：infinilabs 已不再通过 GitHub Releases 发布，改为 release.infinilabs.com
# ============================================================
ARG ES_VERSION=9.4.5
FROM docker.elastic.co/elasticsearch/elasticsearch:${ES_VERSION}

ARG ES_VERSION=9.4.5

# 插件需要写入 /usr/share/elasticsearch/plugins，用 root 安装后再切回 elasticsearch 用户
USER root
RUN bin/elasticsearch-plugin install --batch \
      https://get.infini.cloud/elasticsearch/analysis-ik/${ES_VERSION} \
    && bin/elasticsearch-plugin list
USER elasticsearch

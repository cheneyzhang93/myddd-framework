-- devbox 只负责"建库 + 字符集"，业务表一律由应用自己的 Flyway 迁移创建。
-- xxl_job 库例外：它的表结构归 XXL-Job 发行包所有，需人工放入 seed/xxl-job/tables_xxl_job.sql。
CREATE DATABASE IF NOT EXISTS `myddd_app` DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
CREATE DATABASE IF NOT EXISTS `myddd_app_test` DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
CREATE DATABASE IF NOT EXISTS `xxl_job` DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;

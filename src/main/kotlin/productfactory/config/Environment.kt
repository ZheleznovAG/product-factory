package productfactory.config

/**
 * Профиль окружения выполнения фабрики: влияет на рекомендуемые настройки (policy fail mode,
 * бюджеты, доступные tools). Выбор через переменную окружения FACTORY_ENV.
 */
enum class FactoryEnvironment(val envName: String) {
    LOCAL("local"),
    CI("ci"),
    K8S("k8s"),
    ;

    companion object {
        private val byName = entries.associateBy { it.envName.lowercase() }

        /**
         * Разбор строки в профиль. Допустимые значения: local, ci, k8s (без учёта регистра).
         * При пустом или неизвестном — LOCAL.
         */
        fun parse(value: String?): FactoryEnvironment {
            val raw = value?.trim()?.lowercase() ?: return LOCAL
            return byName[raw] ?: LOCAL
        }

        /** Разбор из переменной окружения FACTORY_ENV. */
        fun fromEnv(): FactoryEnvironment = parse(System.getenv("FACTORY_ENV"))
    }
}

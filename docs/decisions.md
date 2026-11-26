# Decisions log

Неочевидные решения при выполнении плана из `docs/opus_review.md` (раздел 7).
Формат: что сделано → почему → какие были альтернативы.

---

## [1/5] Быстрые исправления падений и совместимости

### Проверка утверждений «(проверить)» по реальному API (MC 26.1.2, Fabric API 0.154.0+26.1.2)
- **`SERVER_STARTING` срабатывает до загрузки уровней — подтверждено.** В `fabric-lifecycle-events-v1` (`MinecraftServerMixin`) событие инжектится в `MinecraftServer.runServer` на `INVOKE MinecraftServer.initServer()`, то есть до `initServer → loadLevel → createLevels`, где грузятся первые чанки. `SERVER_STARTED` инжектится позже, на `buildServerStatus()`. Значит, ревью право: при загрузке на `SERVER_STARTED` первые `CHUNK_LOAD` видели журнал прошлого мира.
- **`net.fabricmc.fabric.api.entity.FakePlayer` существует** (модуль `fabric-events-interaction-v0`, входит в fabric-api, а он у мода жёсткая зависимость) и наследует `ServerPlayer`.
- **`ResourceArgument.resource(CommandBuildContext, ResourceKey<? extends Registry<T>>)` и `ResourceArgument.getResource(ctx, name, key)` существуют** в 26.1.2. Третий аргумент `CommandRegistrationCallback` в Fabric API имеет тип `CommandBuildContext`.

### Переименование пакета
- **Что:** `com.example` → `io.github.dedon.damageblockswap`, клиентский код → `io.github.dedon.damageblockswap.client`; `maven_group=io.github.dedon`. Файлы перенесены через `git mv`, история сохраняется.
- **Миграция данных не нужна.** Персистентные идентификаторы не зависят от Java-пакета: attachment по-прежнему `damageblockswap:applied_version` (собирается из `MOD_ID`), журнал `<world>/damageblockswap_swaps.json`, конфиг `config/damageblockswap.json`, мод-id и ключ клавиши не менялись. Старые миры и конфиги читаются как раньше.
- **Альтернатива:** `maven_group=io.github.dedon.damageblockswap`. Не выбрана: в Maven принято, что group — это владелец, а artifact (`damageblockswap`) задаёт имя проекта.

### Mod Menu без Cloth Config
- **Что:** код экрана Cloth вынесен в отдельный package-private класс `ClothConfigScreen`. `ModMenuIntegration` больше не импортирует ни одного класса Cloth и обращается к `ClothConfigScreen` (через method reference, который резолвится лениво) только после `FabricLoader.isModLoaded("cloth-config")` (id проверен по `fabric.mod.json` Cloth 26.1.154).
- **Отличие от ревью:** ревью предлагало возвращать `parent -> null`. Я возвращаю `ModMenuApi.super.getModConfigScreenFactory()`. По байткоду Mod Menu 18.0.0 видно, что дефолт — это `NullScreenFactory`, и `ModMenu` проверяет `instanceof NullScreenFactory`: такую фабрику он не регистрирует, и кнопка «Config» не появляется. Лямбда `parent -> null` регистрируется как настоящая фабрика, и кнопка остаётся, только ничего не открывает.

### `/dbs lookup` через `ResourceArgument`
- **Что:** `StringArgumentType.string()` с ручными подсказками заменён на `ResourceArgument.resource(buildContext, Registries.BLOCK)`. Теперь принимается `minecraft:stone` (раньше `:` без кавычек ломал разбор), подсказки и ошибка «неизвестный ресурс» ванильные.
- **Альтернатива:** `BlockStateArgument`. Не выбран: он принимает и свойства (`stone[...]`), а команде нужен только тип блока. Тип аргумента ванильный, поэтому сервер без мода на клиенте по-прежнему работает: клиент знает, как его сериализовать.
- Текст ошибки для неизвестного блока теперь ванильный, а не `Unknown block: …`. Это осознанное изменение поведения в пользу корректности.

### `Locale.ROOT`
- `toLowerCase()` → `toLowerCase(Locale.ROOT)` в `Config.normalizeBlacklist`; других вызовов, зависящих от локали, в коде нет. Метод стал package-private ради unit-теста (`ConfigTest` с `Locale.setDefault(tr-TR)`).

### Исключение `FakePlayer`
- Проверка стоит сразу после `instanceof Player`. Креатив и спектатор **не** отсекаются: ревью упоминает их лишь как возможную опцию, а это новая настройка, то есть фича вне пункта 1.

### Загрузка и очистка состояния
- **Загрузка:** журнал грузится на `SERVER_STARTING`. Перед загрузкой на всякий случай сбрасывается состояние сессии (`WorldSwapService.reset()` и кулдауны) на случай, если прошлый сервер не дошёл до `STOPPING`.
- **Остановка:**
  - `SERVER_STOPPING`: сохранить журнал и сбросить очередь, курсор и кулдауны (как раньше, плюс кулдауны);
  - `SERVER_STOPPED`: `SwapTracker.clear()`, чтобы карта свапов и вкладка Mod Menu после выхода из мира не показывали старые данные.
  - Журнал очищается на `STOPPED`, а не на `STOPPING`: после `STOPPING` сервер ещё сохраняет и выгружает чанки, и ни один обработчик (наш или чужой) в это окно не должен увидеть пустой журнал.
- `SwapTracker.clear()` теперь ещё и сбрасывает `dirty`, чтобы забытый пустой журнал никогда не записался поверх файла мира. Это относится и к случаю, когда сохранение на `STOPPING` упало: тогда несохранённые правила теряются, как и раньше. Надёжная запись — это пункт 2.
- `WorldSwapService.reset()` обнуляет `curLevel`, `curChunk`, `curSections`, `curMapping`, курсор и диагностические счётчики, и мёртвый мир больше не удерживается в памяти.
- **Известный остаток (не входит в пункт 1):** на выделенном сервере или при подключении к чужому серверу карта у клиента и раньше была пустой; синхронизация — это пункт 5. Гонка доступа к `SwapTracker` из потока рендера (п. 17 ревью) тоже не входит в пункт 1.

### Ключ `lastPlayerChunk` с измерением
- Вложенный record `DimensionChunk(ResourceKey<Level>, ChunkPos)`, сравнение через `equals` record'а. Если игрок перешёл в то же `(x, z)` другого измерения, пузырь вокруг него обновится.
- **Альтернатива:** `GlobalPos` (измерение + `BlockPos`). Не выбран: он хранит блок, а не чанк, и пришлось бы округлять до чанка вручную.

### Тесты
- В сборку добавлен JUnit 5 (`junit-bom 5.13.4`, `useJUnitPlatform()`). Тесты чистые, без бутстрапа Minecraft. В пункте 1 почти нет чистой логики, поэтому тест один (`ConfigTest`, нормализация чёрного списка). Тесты перестановки и журнала — в пункте 2.
- Gametest'ы не добавлялись: ни одно изменение пункта 1 не проверить дёшево без запуска сервера (порядок lifecycle-событий, Mod Menu UI, разбор команды).

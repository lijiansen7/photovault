# 安卓端「还原到本地」重复任务问题

> 适用版本：1.2.3 及之前；修复后的包为 `dist/photovault-1.2.3-restore-fix.apk`
> 涉及文件：`android/app/src/main/java/com/photovault/app/ui/browse/BrowseViewModel.kt`

## 1. 现象

在相册页连续点击「还原到本机」「还原选中」「一键还原全部」，顶部的还原进度条会一波接一波地出现，看起来像永远跑不完。点几次，就多几波。

`adb shell dumpsys jobscheduler` 或应用内的 WorkManager 数据库里，能看到同一个还原任务被排了多份。

## 2. 背景：WorkManager 的三个概念

要理解这个 bug，先要分清 WorkManager 里三样互相独立的东西。

### 2.1 WorkRequest

一个 `OneTimeWorkRequest` 就是一件待办事项。`WorkManager.enqueue()` 把它塞进队列，系统在合适的时候执行 `Worker.doWork()`。

**普通 `enqueue()` 没有任何去重语义**：调用 10 次就产生 10 个独立任务，各自执行各自的。这是所有问题的起点。

### 2.2 唯一工作名（unique work name）

`enqueueUniqueWork(name, policy, request)` 引入了「名字」这个维度。WorkManager 内部维护一张 `name -> 工作链` 的表，同一个名字在同一时刻**只能有一个未完成的工作**（或一条链）。

名字相同的时候，具体怎么处理由 `ExistingWorkPolicy` 决定：

| Policy | 语义 |
|---|---|
| `KEEP` | 已有同名且**未完成**的工作时，丢弃这次新请求；否则正常入队 |
| `REPLACE` | 取消已有工作，换成新的 |
| `APPEND` | 把新工作接在已有工作**后面**，形成父子链 |
| `APPEND_OR_REPLACE` | 类似 APPEND，但如果已有链处于失败/取消状态，则整条替换 |

关键点：这些策略只对「同名」生效。名字不同，彼此完全不相干。

### 2.3 Tag

`addTag()` 打的标签是**多对多**的：一个任务可以有多个 tag，一个 tag 也可以挂在任意多个任务上。

它**只用于查询**，不影响调度。`getWorkInfosByTagFlow(tag)` 会返回所有带这个 tag 的工作（包括已经跑完、还没被 prune 掉的历史记录）。

## 3. 原实现的三个问题

### 3.1 单张与「还原选中」：普通入队，零去重

```kotlin
// 旧代码
jobs.chunked(200).forEach { chunk ->
    wm.enqueue(                                    // <- 普通入队
        OneTimeWorkRequestBuilder<RestoreWorker>()
            .setInputData(data)
            .addTag(RestoreWorker.TAG)
            .build()
    )
}
```

`restoreOne(p)` 和 `restoreSelected()` 最终都走到这里。用户点 5 次「还原到本机」，就排了 5 个独立任务，每个都会把同一批照片重新下载一遍。

### 3.2 「一键还原全部」：APPEND_OR_REPLACE 是追加

```kotlin
// 旧代码
wm.enqueueUniqueWork(
    "photovault_restore", ExistingWorkPolicy.APPEND_OR_REPLACE,
    OneTimeWorkRequestBuilder<RestoreWorker>()
        .setInputData(workDataOf(RestoreWorker.KEY_ALL to true))
        .addTag(RestoreWorker.TAG)
        .build(),
)
```

这里虽然用了唯一名，但选错了策略。`APPEND_OR_REPLACE` 的语义是「接在后面」——用户每点一次，就往同一条链上再挂一个全量还原任务。链是串行的，所以第一个跑完，第二个立刻开始，如此往复。

这个策略的本意可能是不想打断正在跑的还原，但它把「重复点击」和「想重新跑一次」当成了同一件事。

### 3.3 进度条：按 Tag 聚合，只认 RUNNING

```kotlin
val active = infos.filter { it.state == WorkInfo.State.RUNNING }
val done  = active.sumOf { it.progress.getLong(RestoreWorker.KEY_DONE_BYTES, 0L) }
val total = active.sumOf { it.progress.getLong(RestoreWorker.KEY_TOTAL_BYTES, 0L) }
```

所有还原任务共用 `RestoreWorker.TAG`，进度条把**当前所有 RUNNING 任务的进度加起来**显示。

这个设计在「分批入队」的场景下是对的——200 张以上会切成多个批次同时跑，加起来才是真实总进度。但它对「重复任务」没有任何防御：队列里有几个，就显示几个的合计。

## 4. 为什么表现为「一直有任务出来」

两个效果叠加：

1. **WorkManager 默认并行执行**。线程池不是单线程，队列里堆的多个任务会同时开跑。所以连点两下，可能真的有两个任务在同时下载同一批照片——进度条的总字节数翻倍，速度看起来忽高忽低。
2. **`APPEND_OR_REPLACE` 形成串行链**。一键还原的重复点击是排成链的，前一个结束下一个开始，视觉上就是「一波刚结束又来一波」。

再加上重复任务之间没有协调，同一张照片可能被并发写入 MediaStore 两次。`restoreConflict = "skip"` 的检查是在下载**之前**基于本地已有媒体列表做的，两个任务同时启动时都可能通过检查，最终产生重复文件。

## 5. 修复方案

核心思路：**把「同一批照片的重复请求」折叠成一个任务**。用任务名承载「这批照片是谁」这个身份，用 `KEEP` 做幂等。

### 5.1 一键还原全部：固定名 + KEEP

```kotlin
wm.enqueueUniqueWork(
    "photovault_restore_all", ExistingWorkPolicy.KEEP,
    OneTimeWorkRequestBuilder<RestoreWorker>()
        .setInputData(workDataOf(RestoreWorker.KEY_ALL to true))
        .addTag(RestoreWorker.TAG)
        .build(),
)
```

全部还原的对象是「NAS 上所有照片」，这个集合与点击次数无关，所以固定名字是合理的。只要有一个全量任务在排队或运行，后续点击直接被丢弃。

改 `KEEP` 而不是 `REPLACE`，是为了不打断已经在跑的下载——REPLACE 会取消当前任务，用户点了等于白等。

### 5.2 单张与「还原选中」：名字由内容决定

```kotlin
jobs.chunked(200).forEach { chunk ->
    val data: Data = workDataOf(/* ... */)

    // 唯一名 = 这批照片的 id 集合的哈希
    val name = "photovault_restore_" +
        Integer.toHexString(chunk.joinToString(",") { it.id }.hashCode())

    wm.enqueueUniqueWork(
        name, ExistingWorkPolicy.KEEP,
        OneTimeWorkRequestBuilder<RestoreWorker>()
            .setInputData(data)
            .addTag(RestoreWorker.TAG)
            .build()
    )
}
```

这里不能用一个固定的名字，原因很实际：

- 如果用固定名 `photovault_restore_selected`，用户先还原 A 批、再还原 B 批时，B 会被 `KEEP` 丢掉（因为 A 还没跑完），这是不能接受的。
- 用「这批照片的 id 集合」作为身份，同一批连点必然同名（被折叠），不同批必然不同名（各自排队）。

选 `hashCode()` 而不是直接拼 id 列表，是因为一批最多 200 张、每张 id 32 个字符，直接拼出来的字符串有 6000 多字符，作为内部 key 没有意义。哈希到 32 位足够区分，碰撞概率约 1/2³²。

`chunk.joinToString(",")` 的顺序是稳定的（来自 `items.filter { it.id in selection }`，列表顺序固定），所以同一批照片无论点几次，算出来的名字都一样。

### 5.3 效果对比

| 场景 | 修复前 | 修复后 |
|---|---|---|
| 连点 5 次「还原到本机」（同一张） | 5 个任务并行 | 1 个任务 |
| 连点 5 次「一键还原全部」 | 5 个全量任务串行 | 1 个任务 |
| 还原 A 批，再还原 B 批 | 2 个任务 | 2 个任务（正常） |
| 还原 A 批，等它跑完，再点同一批 | 新建任务 | 新建任务（正常，用户可能确实要重跑） |

## 6. 边界与已知限制

**`KEEP` 不拦已完成的任务**。它只在同名工作处于 `ENQUEUED` / `RUNNING` / `BLOCKED` 时生效。任务一旦进入 `SUCCEEDED` / `FAILED` / `CANCELLED`，再点就会新建——这符合预期，用户删了本地文件后应该能重新还原。

**失败重试有上限**。`RestoreWorker` 结尾是 `if (failed > 0 && runAttemptCount < 2) Result.retry() else Result.success(out)`，最多重试一次。这不会造成无限循环，但重试时会重新遍历整批照片（已成功的会因为 `skip` 策略被跳过，不会重复下载）。

**跨批次仍然并发**。不同 id 集合的任务是并行的，这是刻意的——用户对不同照片的还原请求本来就该各自进行。只保证「同一批不重复」。

**哈希碰撞**。两批不同的照片算出同一个 32 位哈希，后一批会被 `KEEP` 丢弃。概率极低，且后果只是「这次没排上，再点一次会成功」（因为任务已完成后再点会新建）。

## 7. 验证方法

装新包后：

1. 连点 5 次「还原到本机」同一张照片，顶部只应出现一条进度条，跑完即消失。
2. 连点 3 次「一键还原全部」，只应跑一遍。
3. 用 `adb shell dumpsys jobscheduler | grep photovault` 看不到重复的 pending job。

另外，旧版本遗留的排队任务不会被新代码清理掉（它们已经存在于 WorkManager 数据库里）。装完新包后点一次进度条右侧的「取消」（`cancelAllWorkByTag`），把旧任务清空，再从干净状态开始验证。

## 8. 一句话总结

`enqueue()` 没有去重语义，`APPEND_OR_REPLACE` 是追加而不是覆盖，而进度条按 tag 聚合所有 RUNNING 任务——三者叠加，就让「连点」变成了「排队」。修复的做法是把「这批照片是谁」编码进唯一任务名，用 `KEEP` 让重复请求在同名时短路。

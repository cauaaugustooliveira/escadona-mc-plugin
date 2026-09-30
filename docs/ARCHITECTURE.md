# Arquitetura do Escadona

## Runtime por servidor

`ServerRuntimeRegistry` mantém um `EscadonaRuntime` por identidade de `MinecraftServer`. Cada runtime contém `GameManager`, `ArenaManager`, `LobbyManager`, `LuckyBlockTracker`, `LuckyBlockResolver` e `ScoreboardManager`. Não existe singleton global de partida.

Toda mutação de partida, tracker, countdown e mundo ocorre na thread de tick do servidor. O runtime também expõe um `WorldTeleportService` reutilizável, mas ele ainda não é acoplado ao ciclo das partidas.

## Mundos e teleporte

`WorldCommand` delega importação e descoberta ao `WorldImportManager`; o comando não toca filesystem nem constrói níveis. `WorldNameResolver` permanece responsável por resolver destinos já carregados sem ambiguidade.

Os sources mapeados exatos de Minecraft 1.21.8/NeoForge 21.8.54 mostram que `MinecraftServer.createLevels` constrói `ServerLevel` com o `LevelStorageAccess` ativo, `ServerLevelData`, `LevelStem`, listener de chunks, seed de biome e gerador. `ServerLevel` deriva chunks, entidades e POIs de `LevelStorageAccess.getDimensionPath`. A API vanilla não oferece `createWorld`, mas NeoForge expõe `forgeGetWorldMap()` e `markWorldsDirty()` para integração interna. Projetos abertos como [Multiworld](https://github.com/IsaiahMC/multiworld) e [NeoForge World Switcher](https://github.com/GeraldHofbauerWeb/neoforge-world-switcher) confirmam conceitualmente esse lifecycle; nenhuma fonte LGPL foi copiada.

`DynamicWorldLoader` encapsula todo acesso de baixo nível. Ele cria `ImportedServerLevel` com tipo Overworld, gerador Overworld corrente e seed do mapa, conecta uma border delegada, insere o nível no mapa NeoForge, chama `markWorldsDirty` e publica `LevelEvent.Load`. Um Access Transformer torna visíveis somente `MinecraftServer.storageSource` e o `executor` de background que o próprio `MinecraftServer.createLevels()` entrega a todos os `ServerLevel`s vanilla; usar o servidor/main loop como executor impediria o pipeline de chunks de progredir durante uma consulta síncrona. Não há Mixin nem reflection. O override de `getSeed` usa um seed pendente durante o construtor para que `ServerChunkCache` não caia na seed principal. A preparação do spawn é iniciada fora da server thread por `getChunkFuture`: bookkeeping volta à thread correta, enquanto geração e DataFixer avançam sem um `getChunk()` síncrono prender o tick e acionar o watchdog. Antes de cada teleporte, um ticket `PORTAL` de 300 ticks é renovado; a busca de posição segura só ocorre depois do futuro e enquanto o chunk continua residente.

`ImportedLevelData` delega regras globais ao `WorldData` principal, mas mantém nome, spawn, rotação e relógio importados. O `level.dat` externo nunca substitui `server.getWorldData()`.

`WorldImportManager` aceita somente filhos diretos da raiz do servidor ou de `escadona/world-imports`, restringe nomes a `[a-z0-9][a-z0-9_-]{0,63}`, recusa symlinks/traversal, protege o save principal e valida `level.dat`, `DataVersion` e `region/`. A cópia ocorre numa executor single-thread daemon e inclui apenas `region`, `entities` e `poi`; interação com `MinecraftServer` retorna à server thread. A origem permanece como backup.

A transação persiste `IMPORTING`, copia para `.<nome>.importing`, valida e faz rename atômico para `dimensions/escadona/<nome>`. Depois persiste `IMPORTED`, materializa o nível e persiste `LOADED`. No startup, uma transação interrompida com destino completo é retomada; sem destino completo vira `INVALID`. O schema 2 de `JsonWorldRegistry` usa arquivo temporário e move atômico. O startup carrega mundos antes de arenas e sessões.

Somente o Overworld é importado. Dados de jogador, advancements, stats, Nether/End, datapacks e dados globais não entram no storage. Chunks existentes preservam paleta, BlockState e Block Entities; entidades e POIs usam seus region files. Chunks ainda não gerados usam o gerador Overworld principal com a seed importada, limitação explícita para mapas flat/custom. `world unload` e `world delete` permanecem fora de escopo.

`WorldTeleportService` recebe `ServerPlayer` e `ResourceKey<Level>` ou `ServerLevel`. Ele resolve o nível carregado, procura uma posição segura ao redor de `ServerLevel.getSharedSpawnPos()` e usa a assinatura oficial 1.21.8:

```java
ServerPlayer.teleportTo(ServerLevel, x, y, z, Set<Relative>, yaw, pitch, boolean)
```

A mesma instância de player é transferida; nenhum clone é criado. A busca exige world border, piso com colisão, espaço livre para corpo/cabeça e ausência de fluidos, fogo, powder snow e pisos perigosos conhecidos. Falha de nível, posição ou chamada retorna resultado explícito e feedback amigável. O serviço não importa classes client-only nem registra packets próprios.

`MainLobbyService` mantém a configuração global persistida atomicamente em `escadona/main-lobby.json`. O domínio `Arena` não possui lobby próprio: entrada/início validam a existência do destino global, e saída voluntária, término, stop administrativo e recuperação de respawn encerrado convergem para esse mesmo destino. No login, `GameManager` resolve primeiro reconexões e respawns; somente quando o UUID não pertence a uma Match ativa o handler solicita o teleporte silencioso para o lobby principal. A posição configurada passa pela mesma validação server-side de segurança. O schema 2 de Arena remove o campo `lobby`; arquivos schema 1 continuam sendo aceitos e o campo legado é ignorado.

`LobbyManager` compõe `MainLobbyService`, `ArenaManager` e `ArenaResetManager`. Ele concentra contexto de lobby, política do inventário controlado, entrega/remoção do seletor, retorno pós-Match, construção das views de Arena e atualização dos menus abertos. `ServerEventHandler` somente encaminha login, respawn, interação, drop e tick. O papel e os botões usam `DataComponents.CUSTOM_DATA`; nomes visíveis nunca são usados como identidade.

`ArenaSelectorMenu` é um `ChestMenu` server-side cujo `MenuType.GENERIC_9xN` já possui Screen vanilla no cliente. Seus cliques não executam a movimentação padrão e shift-click retorna vazio. Até 54 Arenas o número de linhas é dinâmico; acima disso, cada página usa 45 entradas e reserva a última linha para setas. O conteúdo aberto é recalculado a cada 20 ticks e sincronizado com `broadcastChanges`, sem substituir o container. Todo clique resolve novamente o ID marcado e chama `GameManager.join`.

`GameManager.join` permanece como fronteira transacional única: verifica lobby, Arena, snapshot, dimensão, reset e estado antes de delegar a alocação final a `Match.join`. A lane é ocupada na server thread; portanto duas tentativas para a última vaga são serializadas e apenas uma vence. Tanto o comando quanto o menu recebem a mesma exceção tipada e nenhuma camada de UI mantém contador paralelo.

`LobbyProtectionPolicy` concentra a regra pura de invulnerabilidade e fome: jogadores sem Match são protegidos quando o lobby principal existe; participantes são protegidos fora de `PLAYING` e também quando não estão `ALIVE`. `LivingIncomingDamageEvent` bloqueia dano tanto contra a vítima protegida quanto originado por um atacante protegido. No pós-tick do servidor, comida e saturação são mantidas cheias somente para esses jogadores. Um participante vivo em `PLAYING` não recebe nenhuma dessas proteções.

`PlayerInventoryService` é a única implementação de limpeza completa usada pelos fluxos de lobby. `LobbyManager` chama essa rotina somente depois de um teleporte confirmado e então instala o seletor no slot configurado; `GameManager` converge saída voluntária, respawns de encerramento e início do reset para esse serviço. Isso inclui cursor, hotbar, inventário principal, armadura e off-hand, além de sincronizar menus. Recuperação de uma Match ativa não é um fluxo de lobby e não executa a limpeza.

`VoidRescuePolicy` decide o destino sem acessar o mundo: abaixo de `Level.getMinY`, jogadores sem Match usam o lobby principal e participantes em `WAITING` usam o spawn da lane. Outros estados retornam `NONE`, preservando especialmente a morte normal por void em `PLAYING`. O handler executa a decisão no pós-tick, e os dois serviços de teleporte zeram velocidade e `fallDistance` após sucesso.

Em `PLAYING`, `VoidDamagePolicy` restringe a letalidade imediata a participantes `ALIVE`. No primeiro `LivingIncomingDamageEvent` cuja fonte é `DamageTypes.FELL_OUT_OF_WORLD`, o handler usa a mutação oficial de dano do evento para torná-lo letal; o `LivingDeathEvent` resultante continua atravessando o mesmo `GameManager.onPlayerDeath`, preservando mensagem, inventário e countdown. Proteções de lobby/estado e a invulnerabilidade anti-abates são avaliadas antes dessa conversão.

## Configuração e persistência da arena

Há duas representações distintas:

- `ArenaDraft`: mutável e capaz de representar edição incompleta;
- `Arena`: configuração imutável e validada usada pelas partidas.

Essa separação permite executar comandos em qualquer ordem sem enfraquecer as invariantes da partida. `ArenaValidation` devolve erros e avisos. Erros impedem `save` e `start`; linha de chegada ausente ou fora da região agora é erro.

`JsonArenaRepository` converte explicitamente os objetos de domínio para valores JSON simples. `BlockPos`, `GlobalPos`, `ResourceKey` e outras classes do Minecraft não são serializadas por reflexão. O formato atual tem `schemaVersion: 3` e um arquivo por arena em `<world>/escadona/arenas`; schemas 1 e 2 continuam legíveis. A versão 3 acrescenta o checkpoint opcional de cada combinação lane/seção.

Cada Arena pode definir `deathWait`. Na morte, o jogador é colocado em Spectator e teleportado para essa posição durante o ticket de respawn; o alvo final continua sendo o checkpoint da lane. O campo é opcional para manter compatibilidade com arenas existentes.

`Section` guarda o checkpoint como `GlobalPos` opcional no snapshot imutável da Arena. O rascunho aceita configuração independente por lane e valida que a posição esteja dentro dos bounds. `CheckpointProgress` só atualiza `RacePlayer.checkpoint` quando o tracker confirma que o bloco acabou de transicionar e a contagem quebrada da seção é exatamente um. Assim callbacks repetidos, cancelamentos e a segunda Lucky Block não reatribuem o destino. O checkpoint corrente já faz parte de `MatchSession`, então morte, desconexão e restart reutilizam a mesma posição sem persistência paralela.

## Match

`GameManager` indexa partidas por UUID, arena e jogador. `Match` contém:

- snapshot imutável da arena usada;
- `GameState`;
- jogadores e lanes;
- `RacePlayer` por UUID;
- countdown inicial;
- tick e horário de início;
- `BarrierManager` próprio.

Uma lane é atribuída uma única vez enquanto estiver ocupada. Entrada só ocorre em `WAITING`. A máquina de estados continua explícita:

```text
WAITING -> STARTING -> PLAYING -> FINISHING -> RESETTING -> WAITING
```

Parada durante `STARTING` usa `STARTING -> RESETTING -> WAITING`. A instância terminada é retirada dos índices do `GameManager`. `initialPlayerCount` e `requiredFinishers = min(3, initialPlayerCount - 1)` são fixados em `STARTING`; desconexões não os alteram.

No `join`, a lane é reservada e o jogador é teleportado imediatamente para seu spawn. Dano PvP entre participantes da mesma Match é cancelado em `WAITING` e `STARTING`; somente `PLAYING` habilita combate.

## Scoreboard server-side

O scoreboard é uma camada de apresentação separada em três partes:

- `ScoreboardRenderer` deriva uma `ScoreboardView` imutável diretamente de `Match`, `RacePlayer`, `LuckyBlockTracker`, `BarrierManager`, `RespawnManager` e `ArenaResetManager`;
- `ScoreboardManager` controla ciclo de vida, cache diferencial e transporte;
- `ScoreboardView` limita explicitamente a sidebar às 15 linhas suportadas pelo cliente.

Não existe contador de seção, barreira, respawn, placement ou reset na UI. `STARTING` lê `startingEndsAtTick`; o tempo de corrida usa `Match.startedAt`; Lucky Blocks são consultadas pela chave `matchId + ownerId + laneId + section`; barreiras usam `BarrierCountdown.opensAtTick`; respawn usa `PendingRespawn.dueAtTick`; o percentual vem de `ArenaResetManager.Progress`.

O manager executa uma passagem a cada `scoreboard.updateIntervalTicks`, compara a view nova com a anterior e envia apenas scores alterados ou removidos. Eventos de ciclo de vida relevantes podem solicitar refresh imediato. Logout descarta somente o cache; login renderiza novamente a partir do estado recuperado. Remoção da Match envia a remoção do objective próprio, evitando sidebar fantasma.

Para permitir conteúdo individual sem ocupar o display global de `ServerScoreboard`, cada UUID recebe um `Objective` local de nome `esc_<12 hex>`. Os packets vanilla `ClientboundSetObjectivePacket`, `ClientboundSetScorePacket`, `ClientboundResetScorePacket` e `ClientboundSetDisplayObjectivePacket` são enviados diretamente ao `ServerPlayer`; valores numéricos usam `BlankFormat`. Isso mantém compatibilidade com cliente vanilla. Como o protocolo possui somente um slot `SIDEBAR` por cliente, sidebars de outros sistemas não podem aparecer simultaneamente; uma substituição externa vence visualmente e o Escadona não disputa continuamente o slot.

Os owners internos das linhas usam `esc_ln_<índice>`. Eles nunca começam com `#`: em 1.21.8, `PlayerScoreEntry.isHidden()` trata esse prefixo como score deliberadamente oculto e a GUI o remove antes de renderizar a sidebar.

`ChatMessages` centraliza o feedback textual fora da sidebar. O prefixo, semântica de cores, alertas de barreira, conclusão de seção e linhas de pódio são construídos com `Component`/`ChatFormatting` vanilla. Comandos, teleporte, eventos e broadcasts reutilizam esse helper; títulos permanecem no `GameManager`, mas seguem a mesma semântica visual. Isso evita códigos legados em strings e mantém o mod inteiramente server-side.

`GameManager.onPlayerDeath` retorna um `DeathResult` estruturado. Além de informar se a morte pertence à Match, ele diferencia a primeira ocorrência de callbacks repetidos e expõe somente o killer que passou pelas regras da Match. O handler cancela a morte vanilla e transmite exatamente um componente de chat: mensagem de abate para PvP válido ou a causa de morte localizada do `DamageSource` nos demais casos.

O mesmo fluxo registra no `RacePlayer` apenas mortes cujo killer foi validado como outro participante vivo da Match. Uma janela móvel usa `Instant`: o terceiro abate inclusivo em 30 segundos deixa a proteção pendente. `completePendingRespawn` ativa então um prazo de 30 segundos, sem desperdiçar duração durante o countdown em Spectator. `LivingIncomingDamageEvent` consulta o prazo server-side e cancela qualquer dano recebido. Histórico recente, flag pendente e deadline fazem parte do schema 2 de `MatchSession`; sessões schema 1 recebem estado vazio ao carregar.

`ScoreboardLayout` representa os templates carregados da server config como uma lista independente para lobby e cada estado visível da Match, e `LegacyTextFormatter` converte códigos `&` em `Style`/`Component` vanilla. Entradas `""` são preservadas como linhas vazias — inclusive quando consecutivas — porque cada posição recebe um owner de score distinto. `ScoreboardRenderer.renderLobby` não requer uma Match e usa jogadores online mais as estatísticas do UUID. Durante `RESETTING`, como `matchesByPlayer` já foi liberado, o mesmo objective passa para o layout do lobby. O renderer de Match cria um mapa de placeholders a partir do domínio, expande as linhas especiais `{placements}`/`{results}`, substitui tokens e só então aplica formatação. Templates excessivos são truncados em 15 linhas em vez de derrubar o tick. A configuração altera apenas apresentação: nenhum placeholder pode modificar Match, tracker ou timer.

O acesso à configuração de scoreboard passa por getters tolerantes em `EscadonaConfig`. `ModConfigSpec.ConfigValue.get()` pode retornar `null` enquanto a server config ainda não foi anexada ou durante reload; boolean, intervalo, footer, título e listas usam defaults nesse período. Isso impede unboxing de `null` no tick e permite que a view seja criada antes do carregamento definitivo da configuração.

`PlayerStatisticsService` mantém kills e vitórias acumuladas por UUID em `escadona/player-stats.json`. Escritas usam arquivo temporário e move atômico. Kills são creditadas somente pelo fluxo de morte gerenciado de uma Match em `PLAYING`; vitórias são indexadas também pelo UUID da Match, tornando a recuperação idempotente. O renderer recebe uma cópia somente-leitura desses totais e oferece `{kills}`/`{wins}` aos templates.

## Inicialização da corrida

`GameManager.start` valida toda a operação antes de mudar o estado. Em seguida:

1. resolve o bloco oficial pelo registry;
2. gera IDs estáveis dos trackers dentro da partida;
3. restaura posições que não contêm o bloco esperado;
4. prepara e teleporta jogadores;
5. inicia countdown de três segundos;
6. transiciona para `PLAYING` no tick final.

Trackers de partida carregam `matchId`, além de owner UUID, lane, seção, dimensão, posição, registry ID, estado original e estado lógico. Trackers de debug usam `matchId = null`.

## Progressão individual

O evento de quebra possui duas fases:

1. em prioridade alta, cancela a ação se a partida não estiver em `PLAYING` ou se o UUID não for o proprietário;
2. em prioridade baixa, após outros handlers terem oportunidade de cancelar, efetiva `PENDING -> BROKEN`.

Depois da transição, `GameManager` registra o ID quebrado em `RacePlayer` e consulta todos os trackers da mesma combinação:

```text
matchId + ownerId + laneId + sectionNumber
```

Somente quando o conjunto obrigatório não está vazio e todos estão `BROKEN`, a seção é registrada. `RacePlayer.completeSection` é idempotente, impedindo eventos duplicados e mantendo progresso independente entre jogadores.

## Lucky Blocks destruídas indiretamente

A auditoria central existente foi preservada. Em intervalos configuráveis, cada tracker `PENDING` em chunk carregado é comparado com o bloco resolvido no registry. Qualquer divergência agenda uma restauração atrasada. Antes de restaurar, o estado lógico e físico são conferidos novamente.

Não há uma task por bloco e chunks não são carregados à força. Trackers `BROKEN` nunca são restaurados.

## Barreiras

Cada `Match` possui seu próprio `BarrierManager`. Uma barreira segue:

```text
CLOSED -> COUNTDOWN -> OPEN
```

`startOnce` só aceita `CLOSED`; conclusões posteriores da mesma seção não reiniciam o timer. `BarrierCountdown` mantém os avisos já emitidos, portanto o chat não recebe mensagens por tick.

Quando o countdown vence, `GameManager` substitui por ar todos os blocos no cubo inclusivo da barreira e só então marca `OPEN`. A alteração fica no mundo para ser revertida pelo futuro snapshot/reset completo.

Estados `CLOSED` e `COUNTDOWN` também são materializados no mundo: qualquer posição sem colisão recebe `minecraft:barrier`. Isso garante bloqueio físico mesmo quando o administrador definiu uma região vazia. Recuperação de sessão reaplica a mesma regra, e encerramento remove apenas esses blocos técnicos remanescentes.

## Parada, timeout e encerramento

Parada administrativa:

- cancela countdowns;
- remove todos os trackers daquele `matchId`;
- devolve jogadores online a Survival e ao lobby, limpando inventário, armadura, off-hand e item carregado pelo menu;
- remove os índices de jogador, mas mantém Match e índice da Arena em `RESETTING`;
- inicia a restauração física imediatamente, sem vitória ou pódio.

Timeout preserva placements reais, anuncia o limite e entra em `FINISHING`; não usa o fluxo de cancelamento e não fabrica posições. Depois da deadline, a Match entra em `RESETTING`. A sessão e o índice da Arena só são removidos depois que o reset físico termina com sucesso.

## Snapshot físico e reset incremental

`ArenaSnapshot` é o template imutável da região inclusiva da Arena. Seus metadados versionados contêm ID, dimensão, bounds normalizados, largura/altura/comprimento, total de blocos e data. No schema 2, as posições são implícitas em ordem X/Z/Y e cada bloco ocupa somente um índice `int` em uma paleta de `BlockState`. Cada estado distinto é codificado uma única vez por `BlockState.CODEC`. Block Entities são esparsas, indexadas pela posição implícita e armazenadas com o NBT oficial de `BlockEntity.saveWithFullMetadata`. Isso preserva propriedades orientadas, componentes NeoForge e inventários sem manter milhões de objetos Java por snapshot.

`ArenaSnapshotRepository` grava dois arquivos NBT/GZIP em `<world>/escadona/snapshots/<arena>/`: `snapshot.nbt`, com paleta, vetor de índices e Block Entities; e `metadata.nbt`, um sidecar pequeno com identidade, bounds, contagem e SHA-256 do arquivo compactado. Gravações usam temporários e substituição atômica. O preflight de início e `snapshot info` leem somente o sidecar e calculam o checksum em streaming, sem descompactar ou materializar os blocos. Restore/reset/verify carregam o payload completo e validam schema, volume, tamanho do vetor, índices de paleta e índices de Block Entities. Snapshots nunca são sobrescritos implicitamente. Mudança de dimensão ou bounds os torna incompatíveis.

Snapshots schema 1 não são carregados automaticamente: a ausência do sidecar faz o preflight falhar com instrução para executar `snapshot delete` e `snapshot create`. Essa incompatibilidade deliberada impede que um arquivo legado volte a materializar a antiga estrutura de alto consumo de heap.

A captura valida `maxSnapshotBlocks` e `maxSnapshotChunks` antes de carregar os chunks intersectados, e recusa qualquer entidade não-player. O limite horizontal separado impede que uma região larga e rasa carregue milhares de chunks mesmo com volume de blocos aceitável. Nesta versão a política deliberada é Arena sem entidades permanentes. Players não integram o template.

`ArenaResetManager` mantém um job por Arena e as fases:

```text
CLEAN_INITIAL -> BLOCKS -> BLOCK_ENTITIES -> NEIGHBOR_UPDATES -> CLEAN_FINAL -> COMPLETE
```

Antes dos blocos, entidades temporárias e ticks de blocos/fluidos dentro dos bounds são descartados. `BLOCKS` compara a região inteira, mas só escreve estados diferentes; posições alteradas ou com Block Entity são marcadas em um `BitSet`. Block Entities antigas são removidas sem drops e a lista esparsa das Block Entities originais é recriada com `BlockEntity.loadStatic`. A passagem incremental de vizinhos e fluidos percorre somente os índices marcados, em vez de repetir uma varredura completa da Arena. A limpeza final remove entidades geradas durante o próprio reset. Nenhum `ServerPlayer` é removido.

Cada batch processa no máximo `resetBlocksPerTick` itens. Chunks são obtidos sincronicamente sob demanda em todas as fases; não há force-load persistente para vazar após o job. O progresso expõe o contador real da fase, a varredura principal, o total alterado e `startedAt`. Cada transição de fase gera um único log; não existem logs por bloco.

Uma sessão persistida como `RESETTING` é recuperada como Match bloqueada e o cursor é reiniciado em zero. A operação é idempotente porque o snapshot é a fonte de verdade. Exceções geram estado operacional `FAILED` no manager; a Match e sua sessão não voltam a `WAITING`, e `arena reset retry` recarrega o snapshot e reinicia o cursor. `snapshot verify` oferece validação física completa sob demanda.

## Morte, inventário e reconexão

`LivingDeathEvent` é cancelado durante `PLAYING`, antes do caminho vanilla de drops. O inventário ativo é limpo imediatamente e o jogador permanece vivo em Spectator, evitando entidades de item e qualquer clone de jogador.

`RespawnManager` mantém tickets indexados por `RespawnKey(matchId, playerId)`. Um ticket contém prazo, alvo de retorno, regra efetiva da arena e, quando necessário, um `InventorySnapshot`. O snapshot copia todos os slots do `Inventory`, inclusive equipamento e off-hand, e o slot selecionado. Sua serialização usa `ItemStack.OPTIONAL_CODEC` com os `RegistryOps` do servidor, preservando componentes, encantamentos, nomes, atributos, dano e dados customizados.

O ticket é removido somente depois de uma restauração bem-sucedida. Isso torna callbacks repetidos inofensivos e impede que respawn, morte consecutiva ou encerramento da Match entreguem duas cópias. Logout altera `RacePlayer` para `DISCONNECTED` sem apagar o ticket. Login retoma `DEAD_RESPAWNING` ou completa imediatamente o retorno. Se a Match terminar, o ticket passa a apontar para o lobby; depois de consumi-lo, o inventário é limpo para que kit ou snapshot preservado não escape da partida.

## Persistência e recuperação de sessão

`JsonMatchSessionRepository` grava um JSON por Match em `<world>/escadona/sessions`. O snapshot contém participantes, estado, timestamps, duração máxima, progresso, barreiras, placements e respawns. Eventos significativos gravam primeiro em `.tmp` e usam substituição atômica; nenhum arquivo é reescrito por tick.

No `ServerStarted`, as arenas são carregadas antes das sessões. A recuperação valida schema, arena, dimensão, lanes, UUIDs, contagens e referências. Trackers são derivados novamente de `matchId + lane + seção + posição`, e seus estados `BROKEN` são reaplicados. Arquivos sintática ou semanticamente inválidos vão para `sessions/invalid`.

Deadlines são persistidas em epoch milliseconds. Countdown inicial, barreiras, timeout e FINISHING descontam o período offline. Respawn é a exceção de segurança: volta imediatamente no próximo login.

## Chegada, placements e FINISHING

A chegada é uma `BlockRegion` inclusiva verificada pela posição dos jogadores, sem varredura de blocos. A elegibilidade consulta todos os trackers do proprietário; `currentSection` não é fonte de verdade. Mensagens para jogadores ainda incompletos têm cooldown.

`PlacementManager` registra UUID, posição, duração e timestamp de modo idempotente. Jogadores colocados ficam `FINISHED`/Spectator e não participam de dano, morte ou progresso. Ao alcançar `requiredFinishers`, ou no timeout, a partida entra em FINISHING, congela progressão, bloqueia dano e mostra somente placements existentes.

## Testabilidade

Lógica sem mundo foi mantida em classes pequenas e testada com JUnit:

- atribuição exclusiva de lanes;
- transições e countdown inicial;
- progresso individual e idempotência de seção;
- conjunto obrigatório de Lucky Blocks;
- countdown único e estado da barreira;
- round-trip JSON da arena;
- valor padrão e round-trip de `keepInventoryOnDeath`.
- fórmula de finalistas, placement idempotente e limiares 2/3/4+;
- desconexão preservando lane e progresso;
- recuperação de Match, round-trip de sessão e deadline de barreira.
- bounds/volume e compatibilidade de metadata de snapshot;
- round-trip NBT comprimido com BlockState/Block Entity e rejeição de truncamento;
- cursor incremental/restart e transição persistida de `RESETTING`.
- views de scoreboard para lobby, WAITING, STARTING, PLAYING, FINISHED, DEAD_RESPAWNING e FINISHING, retornando ao lobby em RESETTING;
- cálculo de seção, Lucky Blocks, barreira, placements e reconstrução determinística após reconexão.

Interações que alteram o mundo continuam candidatas a GameTests em uma etapa posterior.

## Limitações deliberadas

- entidades originais não são serializadas; a captura exige região sem entidades não-player;
- alterações fora dos bounds nunca são tocadas;
- o comando de captura é administrativo e síncrono, protegido por limite de volume;
- GameTests de mundo real para fluidos, contêineres e integração com resultados de outros mods permanecem como validação futura.
- o cliente vanilla possui apenas um slot de sidebar; o Escadona não compõe sua view com scoreboards de outros mods/plugins nem restaura uma view anterior desconhecida.

# Escadona

Escadona é um minigame multiplayer server-authoritative para Minecraft 1.21.8 em que jogadores percorrem pistas individuais de Lucky Blocks e abrem barreiras globais ao concluir seções.

## Stack

- Minecraft 1.21.8
- NeoForge 21.8.54
- Java 21
- Lucky Block oficial `1.21.6-13.0`
- Kotlin for Forge 5.6.0, exigido pelo Lucky Block

O projeto é um mod NeoForge nativo. Não usa Bukkit, Spigot ou Paper. Lucky Block e Kotlin for Forge são resolvidos como dependências externas e não são incorporados ao JAR do Escadona.

## Desenvolvimento

1. Instale um JDK 21.
2. Importe o diretório como projeto Gradle.
3. Execute `gradlew.bat clean build` no Windows ou `./gradlew clean build` em sistemas Unix.
4. Para um servidor de desenvolvimento, execute `gradlew.bat runServer`, aceite o EULA em `run/eula.txt` e execute novamente.

## Criando uma arena

Os comandos de edição exigem permissão de operador nível 2. Identificadores aceitam `a-z`, números, `_` e `-`.

```text
/escadona arena create <arena>
/escadona arena setdeathwait <arena>
/escadona arena setregionpos1 <arena>
/escadona arena setregionpos2 <arena>
/escadona arena addlane <arena> <lane>
/escadona arena setspawn <arena> <lane>
/escadona arena addsection <arena> <numero>
/escadona arena setcheckpoint <arena> <lane> <numero>
/escadona arena clearcheckpoint <arena> <lane> <numero>
/escadona arena trackblock <arena> <lane> <numero>
/escadona arena barrierpos1 <arena> <numero>
/escadona arena barrierpos2 <arena> <numero>
/escadona arena finishpos1 <arena>
/escadona arena finishpos2 <arena>
/escadona arena keepinventory <arena> <true|false>
/escadona arena info <arena>
/escadona arena list
/escadona arena save <arena>
/escadona arena snapshot create <arena>
/escadona arena snapshot info <arena>
/escadona arena snapshot delete <arena>
/escadona arena snapshot restore <arena>
/escadona arena snapshot verify <arena>
/escadona arena reset retry <arena>
```

Comandos de posição usam o bloco ocupado pelo jogador. `trackblock` usa a Lucky Block mirada, com alcance de 20 blocos, e valida o registry ID configurado.

`setdeathwait` define onde um jogador fica em Spectator durante os 5 segundos posteriores à morte. Depois da contagem ele retorna normalmente ao checkpoint da própria lane. Se não for configurado, o jogador aguarda no local em que morreu. Execute `arena save` após alterar a posição.

Cada combinação lane/etapa pode ter um checkpoint opcional. Posicione-se no bloco desejado e use `setcheckpoint`; após `arena save`, o jogador conquista esse destino ao quebrar a primeira Lucky Block confirmada daquela etapa. Mortes e reconexões posteriores retornam ao checkpoint conquistado. `clearcheckpoint` remove a configuração sem alterar as demais lanes. Checkpoints precisam ficar dentro da região da Arena.

Para ser jogável, uma arena precisa de região, linha de chegada 3D, pelo menos duas lanes com spawn, uma ou mais seções, Lucky Blocks em cada combinação lane/seção e uma barreira para cada seção que não seja a final. A chegada deve estar dentro da região da arena. O lobby não pertence mais à Arena: todas usam obrigatoriamente o lobby principal configurado por `/escadona lobby set`.

Depois de salvar a configuração, crie o template físico com `arena snapshot create`. O comando nunca sobrescreve um snapshot existente: para aceitar conscientemente um novo estado físico, primeiro use `snapshot delete` e depois `snapshot create`. Alterar dimensão ou bounds invalida o snapshot anterior, e `start` recusa a Arena até que exista um snapshot íntegro e compatível.

`keepInventoryOnDeath` pertence à arena e tem valor padrão `false`. O comando `keepinventory` altera o rascunho; use `arena save` para persistir a mudança. `arena info` e `status` exibem o valor efetivo.

## Persistência

Cada arena é salva em JSON legível em:

```text
<diretório-do-mundo>/escadona/arenas/<arena>.json
```

O formato possui `schemaVersion` e persiste IDs, dimensão, posições numéricas, região, lanes, spawns, seções, checkpoints opcionais por lane, Lucky Blocks, barreiras, linha de chegada e um mapa de configurações futuras. Classes internas do Minecraft não são serializadas diretamente.

Arquivos são gravados primeiro como `.tmp` e movidos para o destino, reduzindo o risco de arquivo parcial. Arenas válidas são carregadas quando o servidor inicia; arquivos inválidos são registrados no log e ignorados.

Partidas não encerradas são salvas em `<diretório-do-mundo>/escadona/sessions/<matchId>.json`. A sessão contém estado, participantes, lanes, progresso, placements, barreiras e deadlines, duração máxima e respawns pendentes. `InventorySnapshot` usa o codec oficial de `ItemStack`, preservando componentes completos. Sessões incompatíveis são movidas para `sessions/invalid/`, sem recuperação parcial.

Templates físicos ficam em `<diretório-do-mundo>/escadona/snapshots/<arena>/snapshot.nbt`. O formato é NBT comprimido por GZIP, schema versionado, gravado por substituição atômica e protegido por SHA-256 do payload. O checksum é calculado por streaming, sem criar uma segunda cópia do snapshot inteiro na heap. Metadados registram Arena, dimensão, bounds inclusivos, dimensões, contagem e data. Cada entrada usa o codec oficial de `BlockState` e, quando existente, o NBT completo produzido pelo próprio `BlockEntity`; orientação, propriedades, componentes e inventários de contêineres são preservados. O preflight de `start` e o comando `snapshot info` validam integridade e compatibilidade sem materializar uma segunda lista de todos os blocos.

## Executando uma partida

```text
/escadona join <arena>
/escadona leave
/escadona start <arena>
/escadona status [arena]
/escadona stop <arena>
/escadona build enable
/escadona build disable
/escadona build status
```

`join`, `leave` e `status` podem ser usados por jogadores. `start`, `stop` e `build` exigem permissão de operador nível 2.

Fluxo atual:

1. jogadores entram em uma arena salva e recebem lanes livres distintas;
   ao entrar, cada jogador é teleportado imediatamente para o spawn da própria lane;
2. `start` valida arena, snapshot físico íntegro/compatível, mínimo de dois participantes, presença online e Lucky Block oficial;
3. a partida passa de `WAITING` para `STARTING`;
4. jogadores são teleportados, colocados em Survival, curados e recebem espada de ferro, picareta de ferro e 16 pães;
5. títulos mostram `3`, `2`, `1` e `COMEÇOU!`;
6. a partida entra em `PLAYING` e registra o tick/horário inicial;
7. trackers são gerados automaticamente para cada jogador, lane e seção;
8. na primeira Lucky Block quebrada de uma etapa, o checkpoint opcional daquela lane é conquistado e persistido;
9. o primeiro jogador que completa uma seção inicia uma única contagem global da barreira;
10. após o tempo configurado, toda a região da barreira vira ar e seu estado passa a `OPEN`;
11. ao terminar `FINISHING`, ou imediatamente após `stop`, jogadores voltam ao lobby com inventário, armadura, off-hand e item do cursor limpos; trackers são descartados e a Match permanece em `RESETTING` até o reset físico concluir;
12. somente depois da restauração completa a sessão é apagada e a Arena fica disponível para outra Match.

PvP entre participantes da mesma Match fica desativado em `WAITING` e `STARTING`. Ele só é liberado quando o estado chega a `PLAYING`.

Durante `PLAYING`, cada vítima mantém uma janela móvel de abates PvP recebidos. Ao ser morta por outro participante três vezes em até 30 segundos, ela é avisada no chat e recebe 30 segundos completos de invulnerabilidade a partir do respawn. Dano ambiental e suicídio não alimentam o contador. A proteção bloqueia todo dano recebido enquanto ativa e seu contador, estado pendente e prazo final sobrevivem a desconexão e restart da sessão.

O primeiro pulso de dano `minecraft:out_of_world` recebido por um participante vivo em `PLAYING` é convertido em dano letal e entra imediatamente no fluxo gerenciado de morte/respawn. Isso não substitui o resgate do lobby nem o retorno ao spawn da lane em `WAITING`. Uma invulnerabilidade anti-abates ainda ativa continua tendo precedência e bloqueia o dano do void até expirar.

O timeout configurado em `maxMatchMinutes` preserva placements existentes e entra em `FINISHING`; ele não inventa posições para quem não terminou.

## Scoreboard da partida

Todo jogador no lobby principal recebe uma sidebar vanilla individual, mesmo sem pertencer a uma Match. Ela mostra jogadores online e as estatísticas persistentes do próprio jogador. Em `WAITING` ela passa a mostrar ocupação das lanes; em `STARTING`, o countdown real da Match; em `PLAYING`, tempo, seção individual, Lucky Blocks da seção, próxima barreira e pódio; durante `DEAD_RESPAWNING`, mostra o prazo do ticket de respawn; jogadores `FINISHED` veem posição e tempo. `FINISHING` apresenta somente resultados existentes. Ao entrar em `RESETTING`, a mesma sidebar é atualizada para o layout do lobby, pois os jogadores já deixaram a partida.

`ScoreboardManager` não mantém progresso ou timers próprios. Ele renderiza o estado autoritativo existente em intervalos configuráveis, mantém um cache da última view e envia somente linhas alteradas. Entrada, saída, início, morte, quebra relevante e reconexão também provocam refresh imediato. Ao sair da Match ou após o reset, o objective do Escadona é removido.

A implementação usa exclusivamente packets vanilla de objective/display/score; clientes não precisam instalar o Escadona. Cada jogador recebe um objective interno exclusivo, enviado diretamente à sua conexão, sem registrar ou substituir objectives no scoreboard global do servidor. A sidebar vanilla só comporta um objective visível por vez: outro mod ou plugin pode substituir visualmente a sidebar do Escadona, e o Escadona não tenta restaurar a sidebar anterior quando sua Match termina.

O título e todas as linhas são editáveis em `serverconfig/escadona-server.toml`. Exemplo:

```toml
[scoreboard]
enabled = true
title = "&6&lESCADONA"
footer = "play.servidor.com"
updateIntervalTicks = 20
placementLine = "{rank_color}{position}º {player}"
resultLine = "{rank_color}{position}º {player} &8- &b{time}"

[scoreboard.lines]
lobby = ["&7Jogadores online: &f{online_players}", "", "&7Kills: &c{kills}", "&7Vitórias: &6{wins}", "", "&8{footer}"]
waiting = ["&7Jogadores: &f{players}/{max_players}", "&7Kills: &c{kills}", "&7Vitórias: &6{wins}", "", "&eAguardando jogadores...", "", "&8{footer}"]
starting = ["&7Jogadores: &f{players}/{max_players}", "&7Iniciando em: &e{starting_seconds}s", "&7Kills: &c{kills} &8| &7Vitórias: &6{wins}", "", "&6Prepare-se!", "", "&8{footer}"]
playing = ["&7Tempo: &f{elapsed}", "&7Parte: &f{section}/{total_sections}", "&7Lucky Blocks: {lucky_progress}", "&7Kills: &c{kills} &8| &7Vitórias: &6{wins}", "", "&7Barreira: &e{barrier}", "", "{placements}", "", "&8{footer}"]
finished = ["&7Posição: &a{position}", "&7Tempo: &f{finish_time}", "", "&7Kills: &c{kills} &8| &7Vitórias: &6{wins}", "{placements}", "", "&8{footer}"]
deadRespawning = ["&7Tempo: &f{elapsed}", "&7Respawn: &e{respawn_seconds}s", "", "&7Kills: &c{kills} &8| &7Vitórias: &6{wins}", "{placements}", "", "&8{footer}"]
finishing = ["&6&lRESULTADO", "", "{results}", "", "&7Kills: &c{kills} &8| &7Vitórias: &6{wins}", "&eReiniciando arena...", "", "&8{footer}"]
```

Cada estado acima é uma lista completa e independente, na ordem exata em que será exibida. Use `""` para pular uma linha; dois valores vazios consecutivos, como `[..., "", "", ...]`, criam duas linhas de espaço. Linhas vazias não são descartadas pelo renderer. É possível remover, acrescentar ou reordenar qualquer linha, respeitando o limite vanilla de 15 linhas após a expansão de `{placements}` ou `{results}`.

As leituras da seção `scoreboard` possuem fallback para os valores padrão. Durante o intervalo em que uma server config ainda não foi anexada, está sendo recarregada ou contém uma entrada ausente, valores `null` do NeoForge não interrompem o tick do servidor; a sidebar continua com o layout padrão e passa a usar os valores personalizados quando eles estiverem disponíveis.

Cores aceitas: `&0`–`&9`, `&a`–`&f` ou hexadecimal `&#RRGGBB`; formatos: `&l` negrito, `&o` itálico, `&n` sublinhado, `&m` tachado, `&k` ofuscado e `&r` reset. Códigos podem ser maiúsculos. Use `&&` para exibir um `&` literal.

Placeholders globais: `{kills}`, `{wins}` e `{footer}`. O lobby também aceita `{online_players}`. Os layouts de Match aceitam `{arena}`, `{players}`, `{max_players}`, `{starting_seconds}`, `{elapsed}`, `{section}`, `{total_sections}`, `{broken_lucky}`, `{total_lucky}`, `{lucky_progress}`, `{barrier}`, `{position}`, `{finish_time}` e `{respawn_seconds}` nos estados correspondentes. Uma linha exatamente igual a `{placements}` ou `{results}` expande o pódio. Seus formatos usam `{rank_color}`, `{position}`, `{player}` e, nos resultados, `{time}`. O resultado final é limitado com segurança às 15 linhas suportadas pelo cliente.

Kills e vitórias são estatísticas permanentes por UUID, gravadas atomicamente em `<diretório-do-mundo>/escadona/player-stats.json`. Uma kill exige que atacante e vítima sejam participantes vivos da mesma Match em `PLAYING`; suicídio, dano ambiental e atacantes externos não contam. A vitória pertence ao primeiro colocado e usa o `matchId` como chave idempotente, impedindo contagem duplicada após restart ou recuperação de sessão.

## Mensagens e feedback visual

Mensagens do chat usam o prefixo dourado `[ESCADONA]` e cores por intenção: verde para sucesso e progressão, amarelo para preparação/countdowns/alertas, vermelho para falhas, aqua para informações e cinza para detalhes administrativos. Resultados usam ouro, branco e bronze para as três primeiras posições. Títulos de início, morte, respawn e chegada seguem o mesmo padrão usando apenas formatação vanilla, sem resource pack.

## Morte, respawn e inventário

Durante `PLAYING`, a morte vanilla é cancelada antes da criação dos drops. O jogador entra em `DEAD_RESPAWNING`, fica em Spectator e retorna ao último checkpoint após `respawnSeconds` (5 segundos por padrão).

Como o evento vanilla é cancelado, o Escadona publica sua própria mensagem global no chat. Abates válidos identificam vítima e killer; mortes sem killer elegível preservam a causa localizada fornecida pelo Minecraft. O anúncio é vinculado à primeira transição para `DEAD_RESPAWNING`, impedindo mensagens duplicadas por callbacks atrasados.

- `keepInventoryOnDeath=false`: o inventário é limpo imediatamente e, no retorno, são entregues somente uma Iron Sword, uma Iron Pickaxe e 16 Bread. Itens obtidos de Lucky Blocks são perdidos.
- `keepInventoryOnDeath=true`: todos os slots, armadura, off-hand, slot selecionado e componentes completos dos `ItemStack` são copiados e restaurados exatamente uma vez. O kit base não é entregue novamente.

Respawns pendentes são identificados por `matchId + UUID`. Desconectar troca o estado para `DISCONNECTED`, mas não remove o snapshot. Ao reconectar — inclusive depois de restart — o retorno é concluído imediatamente e com a regra de inventário correta enquanto a partida estiver ativa. No encerramento, qualquer kit ou snapshot pendente é consumido sem permanecer no lobby: o inventário final é sempre limpo.

## Desconexão e recuperação

Logout não remove o participante, não libera sua lane e não apaga progresso, trackers ou placements. No login, o UUID é reassociado ao mesmo `RacePlayer`, retorna ao spawn da lane com vida/fome seguras e recebe a mensagem de retorno. Trackers não são recriados durante uma reconexão normal.

No início do servidor, arenas são carregadas antes das sessões. Match, jogadores, trackers determinísticos, estados `BROKEN/PENDING`, barreiras, placements e respawns são reconstruídos. Deadlines usam horário absoluto, portanto countdowns, timeout e `FINISHING` descontam o tempo offline.

Uma sessão encontrada em `RESETTING` nunca é promovida a pronta: o reset é reiniciado idempotentemente do bloco zero usando o snapshot. Se houver exceção, a Match continua bloqueada, o erro aparece em `status` e um operador pode tentar novamente com `arena reset retry`. A sessão só é removida após sucesso.

## Snapshot e reset físico

A região de `regionPos1` a `regionPos2` é inclusiva e é a única área de blocos alterada pelo restaurador. Configure-a com margem suficiente para conter explosões, estruturas e fluidos esperados: efeitos que escaparem dos bounds não serão revertidos. Uma área física de reset separada poderá ser adicionada futuramente sem mudar o formato do snapshot.

A captura é recusada acima de `maxSnapshotBlocks`, acima de `maxSnapshotChunks` ou se existir qualquer entidade não-player na região. O limite separado de chunks impede que uma região muito larga e rasa carregue milhares de chunks apesar de possuir volume aceitável. A política desta versão é que a Arena original não contém entidades permanentes; elas não são serializadas. Durante o reset, todas as entidades não-player dentro dos bounds — mobs, animais, armor stands, itens, XP, TNT e projéteis — são removidas antes e depois dos blocos. `ServerPlayer` é sempre ignorado e os participantes são teleportados ao lobby antes da limpeza.

O snapshot schema 2 usa uma paleta de `BlockState`: cada posição é implícita e ocupa um índice inteiro, enquanto cada estado distinto é serializado uma única vez. Block Entities são armazenadas separadamente, apenas nas posições em que existem, com o NBT completo do Minecraft. Em `<world>/escadona/snapshots/<arena>/`, `snapshot.nbt` contém esse payload compacto e `metadata.nbt` contém metadados e o SHA-256 do arquivo. O `start` e `snapshot info` validam o sidecar e o checksum em streaming sem descompactar a Arena inteira, evitando os múltiplos payloads simultâneos que podiam esgotar o heap.

Snapshots criados antes da versão 0.7.0 são incompatíveis de propósito. Para cada Arena existente, execute `/escadona arena snapshot delete <arena>` e depois `/escadona arena snapshot create <arena>`. O snapshot legado não é carregado automaticamente, pois isso reintroduziria o pico de memória que esta versão elimina.

O restaurador trabalha em fases: limpa entidades e ticks antigos; compara/restaura `BlockState` sem drops e sem cascata de vizinhos; recria Block Entities; aplica atualizações de vizinhos e agenda fluidos originais; faz a limpeza final. A região completa é percorrida somente na comparação principal. Block Entities usam uma lista esparsa de índices e atualizações de vizinhos são executadas somente nas posições realmente alteradas. Cada batch processa no máximo `resetBlocksPerTick` itens por tick. Chunks são carregados sob demanda de forma síncrona e nenhum ticket permanente é criado.

`status [arena]` mostra a fase atual, o percentual e contador próprios da fase, a varredura principal, a quantidade de blocos alterados, o horário inicial e eventual falha. Transições de fase também são registradas uma vez no log, sem mensagens por bloco. `snapshot verify` realiza uma comparação completa manual de blocos e Block Entities; por poder carregar e percorrer toda a região, deve ser usado administrativamente fora de horários críticos.

`/escadona leave` só remove participantes em `WAITING`. Depois do início, o comando é recusado para impedir troca de lane, reset de progresso ou novo kit; um operador pode usar `/escadona stop <arena>`.

## Chegada, placements e pódio

A posição dos jogadores `ALIVE` é comparada com a região inclusiva da chegada. Cruzar só conta quando todos os trackers de `matchId + ownerUUID` estão `BROKEN`; uma mensagem com cooldown avisa quando ainda existem blocos pendentes.

Placements são idempotentes e ordenados pelo servidor. A quantidade necessária é calculada uma vez no início:

```text
requiredFinishers = min(3, initialPlayerCount - 1)
```

Quem termina recebe título, tempo, `PlayerState.FINISHED` e Spectator. Ao atingir a quantidade necessária, a Match entra em `FINISHING`, anuncia somente posições existentes e aguarda `finishingDurationSeconds` antes de encerrar.

## Progressão e barreiras

Progresso é individual. Uma barreira aberta não conclui seções de jogadores atrasados. Uma seção só é registrada quando todos os trackers daquela partida, proprietário, lane e seção estão `BROKEN`.

O countdown usa ticks do servidor, nunca `Thread.sleep`. O valor padrão é 60 segundos e os avisos são enviados em 30, 10, 5, 4, 3, 2 e 1 segundos. Estados das barreiras:

```text
CLOSED -> COUNTDOWN -> OPEN
```

Ao iniciar ou recuperar uma Match, espaços sem colisão dentro de regiões `CLOSED`/`COUNTDOWN` recebem blocos vanilla `minecraft:barrier`. Assim, a região impede passagem mesmo quando foi configurada no ar. Ao abrir, toda a região vira ar; ao cancelar/encerrar, somente os blocos técnicos ainda presentes são removidos.

O tracker continua cancelando a quebra de Lucky Blocks `PENDING` por não proprietários e restaurando posições `PENDING` destruídas ou substituídas indiretamente.

## Debug

Os comandos anteriores continuam disponíveis para testes isolados:

```text
/escadona debug track
/escadona debug list
/escadona debug reset
/escadona debug status
/escadona debug sessions
```

## Mundos e teleporte administrativo

Os comandos abaixo exigem operador nível 2 e funcionam inteiramente no servidor:

```text
/escadona world list
/escadona world load <nome>
/escadona world tp <nome>
/escadona world tp <nome> <player>
```

Desde a versão 0.8.0, `world load` importa uma pasta comum de save Minecraft; não é necessário criar datapack, dimensão JSON ou editar NBT. Com o servidor em execução, coloque o mapa em um destes locais:

```text
<raiz-do-servidor>/mapa_escadona/
<raiz-do-servidor>/escadona/world-imports/mapa_escadona/
```

A pasta precisa conter `level.dat` legível e `region/`. Nomes aceitam somente letras minúsculas ASCII, números, `_` e `-`. A pasta dedicada tem prioridade quando há dois mapas com o mesmo nome. `/escadona world load mapa_escadona` valida versão e localização, copia o mapa em uma worker controlada e publica a cópia atomicamente. Ao concluir, o mundo fica disponível imediatamente como `escadona:mapa_escadona`; restart normalmente não é necessário. O chunk de spawn é preparado assincronamente — inclusive quando exige DataFixer ou geração de dependências — para nunca bloquear um tick aguardando `getChunk()`. O comando informa o início e depois o resultado final da operação.

A origem não é movida nem apagada. Somente o Overworld físico é copiado: `region/`, `entities/` e `poi/` vão para `<save-principal>/dimensions/escadona/<nome>/`. Isso preserva chunks, construções, Block Entities, entidades e POIs. O `level.dat` é lido para `DataVersion`, seed, spawn e rotação, mas não substitui o `WorldData` global do servidor. `playerdata`, `advancements`, `stats`, Nether, End, datapacks e dados globais como scoreboard, command storage, boss events, forced chunks e map data não são importados.

O loader cria um `ServerLevel` vanilla com storage próprio, tipo de dimensão Overworld e seed importada, registra-o no lifecycle do NeoForge e aquece o chunk de spawn. Esse acesso interno é restrito a um único componente e usa um Access Transformer apenas para obter o `LevelStorageAccess` ativo; não há reflection nem Mixin. Novos chunks fora da área já existente usam o gerador Overworld do servidor principal com a seed importada. Portanto, mapas com gerador flat/custom devem manter a arena dentro dos chunks previamente gerados.

`world list` mostra `IMPORT_AVAILABLE`, `IMPORTING`, `IMPORTED`, `RESTART_REQUIRED`, `LOADED` ou `INVALID`. O registro transacional schema 2 fica em `<diretório-do-mundo>/escadona/worlds.json` e guarda nome, ID, origem, storage, estado, data, DataVersion, seed e spawn; referências runtime nunca são serializadas. Uma queda em `IMPORTING` é detectada no startup: storage já publicado é retomado, e cópia incompleta é marcada inválida. Mundos importados são materializados antes de arenas, snapshots e sessões, permitindo recuperar Matches neles.

`world tp mapa_escadona` teleporta ao spawn seguro do mapa; a forma sem `<player>` exige executor jogador. `tp` nunca carrega um mundo implicitamente. Registros legados da versão 0.6.x são preservados como `INVALID`, pois não possuem metadados de importação; importe novamente a pasta original. O servidor precisa ter os mesmos mods/registries usados pelo mapa. `world unload` e `world delete` continuam fora de escopo.

O destino é procurado ao redor do spawn compartilhado do mundo. A posição precisa estar dentro da world border, possuir piso sólido, dois blocos livres e não conter fluido, fogo ou piso perigoso conhecido. O comando falha sem modificar o jogador quando o mundo não está carregado ou nenhuma posição segura é encontrada.

## Lobby principal

O lobby global é configurado por um operador posicionado no local desejado:

```text
/escadona lobby set
/escadona lobby tp
/escadona lobby info

# alias legado compatível
/escadona mainlobby set
/escadona mainlobby info
```

A dimensão, posição e rotação são persistidas em `<diretório-do-mundo>/escadona/main-lobby.json`. Em todo login, jogadores que não pertencem a uma Match ativa são teleportados para esse ponto. Participantes recuperados de uma sessão continuam seguindo o fluxo da Match e não são desviados para o lobby principal. Entrar ou iniciar uma Arena exige que esse lobby esteja configurado e com sua dimensão carregada; ao sair voluntariamente, ao terminar a partida ou ao encerrar administrativamente, todos retornam a ele.

Jogadores no lobby principal são invencíveis e permanecem com 20 pontos de comida e saturação restaurada. A mesma proteção vale para participantes em `WAITING` e `STARTING`. Assim que um jogador vivo entra em `PLAYING`, dano e fome funcionam normalmente; participantes mortos, finalizados ou em `FINISHING` continuam protegidos. A proteção de jogadores sem Match só é ativada quando um lobby principal está configurado.

Jogadores sem Match não podem quebrar blocos enquanto o lobby principal estiver configurado. Participantes de uma Arena também não podem quebrar nenhum bloco durante `WAITING`; a tentativa é cancelada no servidor e informa o motivo na action bar. Essa regra não altera a quebra normal em `PLAYING` nem a validação de propriedade das Lucky Blocks.

Administradores podem liberar a própria quebra temporariamente com `/escadona build enable`, consultar com `/escadona build status` e voltar à proteção com `/escadona build disable`. O bypass é individual, exige operador nível 2, não ignora as regras de Lucky Blocks da partida e é removido automaticamente ao desconectar ou reiniciar o servidor.

Todo teleporte bem-sucedido ao lobby principal limpa integralmente o inventário do jogador, incluindo hotbar, inventário principal, armadura, off-hand e item carregado pelo cursor. Saída voluntária da arena e retorno ao lobby após término/reset usam a mesma rotina. O inventário só é limpo depois de o teleporte confirmar sucesso; uma falha de destino não destrói os itens. Reconexões destinadas a uma Match ativa não passam por essa limpeza.

### Seletor vanilla de Arenas

Ao entrar ou retornar ao lobby, o inventário controlado do lobby é limpo e um papel `Selecionar Arena` é colocado no slot configurado — por padrão, o índice `4` da hotbar. O papel possui marca em `DataComponents.CUSTOM_DATA`; papéis comuns, mesmo renomeados, não abrem o seletor. O item oficial não pode ser usado nem dropado e é removido de containers/crafting ou recolocado no slot correto pelo servidor. Ao entrar em uma Match, todos os itens exclusivos do lobby são removidos.

O clique direito abre um container vanilla intitulado `Escolha uma Arena`, sem Screen, packet ou asset client-side customizado. Cada Arena válida com snapshot compatível e dimensão carregada aparece como `FILLED_MAP`, mostrando ocupação real da `Match`, capacidade derivada das lanes e um estado amigável: `Aguardando`, `Iniciando`, `Em partida`, `Finalizando`, `Restaurando` ou `Indisponível`. Arenas administrativas incompletas ou sem snapshot válido não aparecem para jogadores.

O menu usa de uma a seis linhas conforme a quantidade de Arenas. Acima de 54 entradas, seis linhas são usadas, 45 slots ficam reservados às Arenas e a última linha oferece paginação. Arenas são ordenadas por disponibilidade e depois alfabeticamente. Enquanto o menu permanece aberto, o servidor atualiza seus itens a cada 20 ticks sem recriar o container.

O clique em uma Arena fecha o menu e chama diretamente `GameManager.join`, a mesma autoridade usada por `/escadona join`. Estado, snapshot, dimensão, reset e última lane são revalidados nesse momento; o contador exibido nunca concede entrada por conta própria.

O servidor também verifica quedas abaixo do limite inferior da dimensão. Um jogador sem Match volta ao spawn do lobby principal — passando pela limpeza normal de inventário — enquanto um participante em `WAITING` retorna ao spawn da própria lane. A velocidade vertical e a distância de queda são zeradas após o resgate. Em `PLAYING`, o resgate não interfere: cair no void continua sendo uma morte normal da partida e usa o respawn configurado.

## Configuração

O arquivo `serverconfig/escadona-server.toml` oferece:

- `luckyBlockId`, padrão `lucky:lucky_block`;
- `trackerScanIntervalTicks`;
- `trackerRestoreDelayTicks`;
- `barrierCountdownSeconds`, padrão 60;
- `respawnSeconds`, padrão 5;
- `maxMatchMinutes`;
- `finishingDurationSeconds`, padrão 10.
- `maxSnapshotBlocks`, padrão 2.000.000;
- `maxSnapshotChunks`, padrão 512;
- `resetBlocksPerTick`, padrão 2.000.
- `lobby.selectorSlot`, de `0` a `8`, padrão `4`;
- `scoreboard.enabled`, padrão `true`;
- `scoreboard.footer`, padrão `Escadona`;
- `scoreboard.updateIntervalTicks`, padrão `20`.
- `scoreboard.title`, `scoreboard.placementLine` e `scoreboard.resultLine`;
- listas `scoreboard.lines.lobby`, `waiting`, `starting`, `playing`, `finished`, `deadRespawning` e `finishing`.

## Limitações conhecidas

- movimentação não é congelada durante o countdown inicial;
- entidades originais não são preservadas: a captura exige uma região sem entidades não-player;
- efeitos que ultrapassem `regionPos1/regionPos2` não são restaurados;
- GameTests de mundo dedicado continuam recomendados para validar integrações específicas de outros mods de Lucky Block.

Veja [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) para as decisões internas.

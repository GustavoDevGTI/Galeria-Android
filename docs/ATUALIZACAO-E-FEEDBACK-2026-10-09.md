# Recuperação após atualização e retorno visual de carregamento

## Contexto e limite do diagnóstico

Foi relatado mau funcionamento ao atualizar de 0.8.62 para 0.8.65, resolvido após reinstalar. Sem os dados/logs anteriores do aparelho não é possível atribuir uma causa única a esse caso. A inspeção encontrou riscos concretos: ausência de invalidação de metadados derivados na troca de versão; carregamentos sem retorno de erro; repetição sem limite de varreduras concorrentes; notificação de alteração na interface disputando o mesmo lock das transações do catálogo.

## Alterações

- `GalleryUpgradeCoordinator`: preparação em segundo plano na primeira abertura de cada versão. Cancela manutenção antiga e invalida uma lista explícita de metadados derivados. A conclusão é gravada somente após sucesso; uma falha permite nova tentativa. Compatível com Android 6/API 23, sem dependências novas.
- Preserva o banco e suas migrações, ordem personalizada, preferências, favoritos, pins, capas, classificação de ocultos, lixeira e cache privado de thumbnails. Não altera nem exclui mídia do usuário.
- As telas inicial e de álbum encerram o indicador e informam falhas de leitura. Coleções agregadas verificam a validade do catálogo antes de usar suas páginas.
- A sinalização de alterações deixa de esperar o lock usado em leituras/gravações longas. O controle por revisão continua impedindo que uma varredura antiga limpe uma alteração mais recente.
- Varreduras concorrentes têm até três tentativas por execução. Manutenção pode tentar posteriormente; atualização solicitada pelo usuário encerra com falha em vez de manter seu indicador durante o backoff. Cancelamento é verificado na caminhada de diretórios.
- Linha do tempo/carrossel: preparação antecipada mantida; quando ainda não está pronta, mostra imediatamente um indicador em sua área, bloqueia o botão e ignora cliques repetidos. A barra simples de reprodução permanece disponível. Conclusão remove o indicador e libera o botão. Falha ou espera de 15 segundos libera nova tentativa, sem abertura tardia de um pedido abandonado. A frase da primeira implementação foi substituída pelo ícone circular conforme solicitação posterior.
- `ActivityLoadingIndicator`: retorno padronizado, sem confirmações, nas operações de arquivo e na preparação de destinos de Mover/Copiar. Pedidos repetidos não são enfileirados; erros liberam a interface. Encerrar a tela descarta a entrega visual, mas não interrompe pela metade uma cópia/movimentação já iniciada.
- “Carregar ocultos”: indica trabalho em andamento e bloqueia o botão enquanto o pedido está ativo.

## Validação de atualização por cima

O teste usa APK debug compilado do tag `v0.8.62` e o APK debug candidato, com a mesma assinatura de desenvolvimento. Não substitui a validação futura do APK release assinado nem reproduz integralmente a biblioteca do telefone.

```powershell
./scripts/run-upgrade-validation.ps1 -PreviousApk <apk-debug-da-0.8.62> -ResetEmulatorFixture
```

Exclusivo para emulador: remove a instalação de TESTES somente na preparação inicial, instala a versão antiga, cria mídia e estado antigo, instala a candidata usando `adb install -r` e verifica os dados preservados. Não limpa dados entre as duas instalações. O banco parte do schema 2 e deve migrar para 3; uma entrada obsoleta do catálogo deve desaparecer, enquanto mídia real e ordem personalizada permanecem. A saída inclui hashes dos APKs e relatórios de cada fase.

O runner normal continua executando AndroidJUnitRunner. As fases de instalação são um caminho explícito de fixture, selecionado apenas pelo script; não são testes ignorados nem fazem parte do APK de produção.

## Testes novos e política

Sete casos focados: três de atualização/falha/concorrência; dois de carregamento do carrossel (sucesso e timeout/repetição); dois de retorno e exclusão de pedidos duplicados em operações/destinos. Complementam as migrações existentes e usam bloqueios determinísticos, sem esperar o timeout real de 15 segundos.

Antes de publicar, executar também a regressão de entrega. O teste de instalação deve ser repetido sobre o candidato final, e não usar instalação limpa como substituto de atualização. Não há push/release automático nesta alteração.

## Diagnóstico da primeira regressão completa

A execução `20261009-104024-105-37fc3fce` foi corretamente rejeitada: 111/113 casos instrumentados aprovados, com falhas em fast scroll e no clique de rotação do submenu. O relatório original foi preservado; a repetição focada que aprovou os sete casos dessas áreas não foi promovida a aprovação de entrega.

- Na rotação, o log do Espresso registrou `MotionEvents: Overslept and turned a tap into a long press`. O teste de integração do submenu passou a usar o acionamento semântico existente nos demais painéis (`performClick` na View visível/clicável). Continuam obrigatórias as duas verificações finais: EXIF de 90 graus e imagem efetivamente exibida na nova orientação. Os testes de arraste e navegação continuam usando eventos de toque reais.
- A fixture de fast scroll substitui apenas o catálogo de teste por 260 entradas sintéticas, sem arquivos correspondentes no MediaStore. Uma mudança tardia do provider invalida legitimamente essa fixture. A espera por estabilidade já existente foi ampliada para uma janela quieta de 1,5 segundo, observando notificações e token de geração, sem repetir a asserção de sucesso. Uma eventual nova falha inclui número de linhas, estado dirty e tokens armazenado/atual. A primeira falha não registrava esses tokens; portanto sua causa exata não é tratada como comprovada.
- Falhas de rotação do app agora também deixam diagnóstico no log, além da mensagem já existente na interface. Não foi alterado o algoritmo de rotação.

## Resultado da etapa de atualização, antes da padronização visual — 09/10/2026

- Compilação debug e APK de testes: aprovados.
- 229 testes unitários aprovados, sem ignorados.
- Lint: zero erros e 128 avisos. Os avisos não foram ocultados nem tratados como erros corrigidos.
- Regressão completa: 113/113 testes no emulador Android 16/API 36, sem ignorados, em 418,7 segundos. Entrega completa (compilação + verificações + interface) em 537,4 segundos.
- Ferramentas de validação: 45 contratos aprovados.
- Instalação por cima repetida com o APK final: 0.8.62 debug → candidata debug baseada na 0.8.65, sem apagar dados entre as duas instalações. Mídia real, favoritos, ocultos, pins, capa, ordem personalizada e marcador privado de cache preservados; banco migrado de 2 para 3; entrada obsoleta descartada.
- Fontes do repositório e da cópia local usada para compilar têm o mesmo fingerprint. A aprovação foi revalidada no repositório usando os hashes dos relatórios e APKs copiados.

Evidências locais (relativas à raiz do projeto):

- `app/build/reports/validation/20261009-105611-723-a8a3c4ac/result.json`
- `app/build/reports/validation/delivery.json`
- `app/build/reports/upgrade/20261009-110527/result.json`, `seed.txt` e `verify.txt`
- `app/build/reports/lint-results-debug.html`

SHA-256 do APK debug desta etapa: `8B3A88A247D63D152D8194494CFC1FF34C0E57CE4A7EA9B8D17116B6DB50AE5C`. O recibo de entrega acima não aprova alterações posteriores: o fingerprint deve corresponder às fontes finais antes de publicar.

Ainda não publicado: versão de distribuição não foi incrementada, não houve push nem release. A aprovação debug não substitui a compilação/assinatura e verificação do APK release ao publicar. Os testes de instalação reinicializaram somente a instalação de teste no emulador dedicado antes de preparar a versão antiga; nenhum celular foi acessado ou apagado.

## Padronização do indicador, sem frases visíveis

Solicitação posterior: usar o símbolo de atualização existente em vez de frases de carregamento.

- `LoadingIndicatorView` reutiliza `CircularProgressDrawable`, o mesmo indicador do gesto de atualizar, sem dependência adicional. Cores acompanham o contexto; descrições ficam disponíveis para acessibilidade, sem texto na tela.
- Aplicado ao carrossel de vídeo e Motion Photos, preparação de Motion Photos, carregamento inicial de álbuns/mídias, busca de ocultos e operações/destinos de arquivos.
- O retorno é discreto: indicador no próprio espaço em carregamento; nas operações bloqueantes, painel central compacto. Não há confirmação adicional.
- Mantidos bloqueio de pedidos repetidos, conclusão, timeout e mensagens de erro. O botão de carregar ocultos mantém seu espaço durante a operação.
- A animação para quando o indicador é ocultado ou removido da tela. Quando o Android desativa animações, permanece um símbolo estático de atualização.
- A orientação de design foi aplicada à consistência visual e ao tamanho do indicador, sem alterar a lógica de reprodução, seleção ou operações.

Validação desta etapa:

- Debug e APK instrumentado compilados; 229 unitários e 45 contratos das ferramentas aprovados. Lint: zero erros e 129 avisos, sem supressões novas.
- 55/55 testes das áreas Appearance, Timeline e Albums aprovados, sem ignorados; teste adicional do gerenciador de ocultos aprovado (1/1). As capturas do indicador na operação e no carrossel foram revisadas visualmente.
- A primeira execução destas áreas ficou em 54/55: `ActivityScenario.close()` falhou com `Current state was null unexpectedly` durante a transição após mover o último item. O teste agora aguarda e exige a destruição do álbum de origem antes de encerrar o cenário, como já ocorre no teste equivalente iniciado pelo álbum. Asserções de destino/contagem permanecem. Não houve mudança na navegação do app para acomodar o teste.
- A primeira revisão visual detectou um painel largo: o listener do posicionamento genérico sobrescrevia a largura compacta. O indicador bloqueante passou a configurar sua própria janela de 80 dp; teste exige largura máxima de 100 dp. A repetição de 55 casos usa esse ajuste final.
- Atualização por cima repetida com este APK: 0.8.62 debug → candidata debug, sem limpar dados entre instalações; preservação e migração aprovadas.
- Fontes locais e do repositório: fingerprint `B33B8449DDC7183FD34199E11A188358CE02271B1253E251E9206CEF3AC67B51`.

Evidências: `app/build/reports/validation/20261009-115125-479-1f41a324/result.json`, subpasta `hidden-dialog`, capturas `loading-operation-qa.png` e `loading-filmstrip-qa.png`; atualização em `app/build/reports/upgrade/20261009-115737/result.json`. A execução rejeitada está preservada em `app/build/reports/validation/20261009-114517-330-2fd6f2b0`.

SHA-256 do APK debug desta etapa: `17D493D31003616BF9964EEDAAA41B1DD785AA6F370236FD7EE84037C97A27AF`.

Não houve push/release ou incremento de versão. Esta validação é por áreas, não uma nova aprovação completa de entrega: antes de publicar é necessário executar `run-validation.ps1 -Delivery` sobre as fontes finais (inventário atual: 114 casos instrumentados). O recibo completo anterior permanece histórico e não foi substituído por um recibo parcial.

## Preparação da publicação 0.8.66

Após autorização de publicação, versão/código foram incrementados para `0.8.66`/`8066`. A regressão completa `20261009-142236-333-267b5e0d` aprovou 229 unitários, 114 instrumentados sem ignorados, lint sem erros (129 avisos) e 45 contratos das ferramentas. Fingerprint final: `CA29B714700C4CA3737C93254AF58B7A88B98D09FE433C350C9FF8B4D4D6930B`.

A atualização debug 0.8.62→0.8.66 foi repetida e aprovada, sem limpar dados entre instalações: `app/build/reports/upgrade/20261009-143213/result.json`. A aprovação parcial anterior não foi usada como aprovação de entrega. Build release, assinatura e integridade são documentadas nas [notas da 0.8.66](RELEASE-0.8.66.md).

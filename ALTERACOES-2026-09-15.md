# Atualização imediata das pastas e posicionamento dos painéis

## Comportamento implementado

1. Uma exclusão/movimentação só retira os itens da interface depois de sucesso confirmado. A lista de arquivos concluídos é preservada separadamente da quantidade solicitada: falhas parciais não retiram arquivos que permaneceram na origem.
2. A grade aplica a retirada imediatamente. O catálogo persistido é sincronizado sem aguardar o debounce de 5 segundos do observador. Operações originadas no visualizador retornam os itens alterados à tela de álbum.
3. A tela principal projeta a nova contagem dos álbuns e remove da lista os álbuns sem mídias. Movimentação não diminui a quantidade da coleção global “Todas as mídias”. Uma leitura posterior atualiza capas e demais metadados.
4. Quando uma movimentação deixa o diretório de origem vazio, abre-se o destino. No fluxo álbum → visualizador, o resultado volta ao álbum e substitui a origem vazia pelo destino, evitando que Voltar retorne à pasta esvaziada.
5. A decisão de redirecionar usa o diretório físico, não o resultado filtrado da grade. Arquivos não relacionados e subpastas impedem uma falsa conclusão de vazio; `.nomedia` sozinho é ignorado. Quando a listagem física não é autorizada ou é indeterminada, não há redirecionamento automático. A atualização das mídias autorizadas continua funcionando.
6. O estado de invalidação é separado entre catálogos visível e completo. Uma varredura antiga não pode limpar a marca de uma operação mais recente. Uma carga antiga da fila do visualizador também não pode restaurar o item removido.

Não houve mudança de política de lixeira/exclusão definitiva, localização dos arquivos, permissões solicitadas ou escopo do app. A comparação de URIs mantém a regra de identidade já existente no projeto.

## Painéis

O histórico anterior à uniformização lateral foi consultado. “Exibir/ocultar pastas” voltou a um painel central com margens equivalentes. Mensagens e confirmações também são centrais. Ordenação, filtros, criação de pasta, menus de opções e seleção do destino permanecem laterais. As cores, os controles e as ações foram preservados.

O teste de alinhamento dos controles do gerenciador agora compara o centro vertical de cada controle: comparar a borda superior de um rótulo curto com a de um botão de altura fixa não mede se estão na mesma linha.

## Testes adicionados

- 8 instrumentados: exclusão imediata sem reaparecimento, movimentação parcial, movimentação de todos os arquivos, busca filtrada, falha de movimentação, movimentação do último arquivo pelo visualizador, cancelamento de confirmação central e contagens da tela de álbuns.
- 3 instrumentados: invalidação independente de visíveis/ocultos, proteção contra varredura anterior à nova operação e compatibilidade da marca antiga de cache.
- 4 unitários: diretório vazio, somente `.nomedia`, arquivos/subpastas remanescentes e diretório desconhecido/inexistente.

Os testes criam suas próprias mídias e limpam esses arquivos ao final. O acesso de gerenciamento é concedido à instalação de teste no emulador; não é revogado dentro do processo instrumentado, porque o Android mata esse processo na revogação.

## Validação final

Validação funcional concluída no emulador Galeria_API_36 (Android 16): 55 testes unitários e 34 instrumentados aprovados, sem falhas nem testes ignorados. `assembleDebug`, `assembleRelease` (com R8 e assinatura local) e `lintDebug` aprovados. Lint: 0 erros e 90 avisos; não foram suprimidos avisos. Em relação aos 87 iniciais, há duas sugestões adicionais de uso de KTX e uma recomendação de versão do plugin Android.

O APK final foi identificado como `com.galeria.android`, `versionCode` 8051 e `versionName` 0.8.51. Os APKs 0.8.50 e 0.8.51 passaram pela verificação de assinatura com o mesmo certificado. No emulador, a instalação da 0.8.50 seguida de `adb install -r` da 0.8.51 foi aceita; o pacote atualizado abriu e permaneceu em execução sem crash registrado.

Uma execução intermediária foi abortada externamente; após reiniciar o emulador, a suíte completa concluiu com sucesso. Resultado registrado em `app/build/outputs/androidTest-results/connected/debug` e relatório HTML em `app/build/reports/androidTests/connected/debug/index.html`.

Benchmarks da versão final: 4 casos aprovados (3 macrobenchmarks e 1 geração de Baseline Profile), sem falhas nem testes ignorados. Relatório em `benchmark/build/reports/androidTests/connected/benchmark/index.html`. O perfil gerado não substituiu automaticamente o perfil versionado. Não há afirmação de ganho de desempenho absoluto: esses resultados são do emulador, sem comparação controlada em aparelho físico.

Esta etapa foi preparada para publicação como versão 0.8.51. O APK histórico 0.8.50 permanece no histórico do Git e a nova distribuição é feita preferencialmente pelo GitHub Releases. Testes em emulador não garantem comportamento em todos os aparelhos, níveis de acesso e cartões SD.

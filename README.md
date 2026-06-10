# Galeria Android

Primeira versao de um app de galeria Android feito em Java nativo.

## Recursos atuais

- Lista fotos e videos do aparelho usando MediaStore.
- Abre foto ou video em tela de detalhe.
- Exclui midia com confirmacao do Android quando necessario.
- Oculta itens copiando para uma area privada do app e removendo o original da galeria publica.
- Mostra itens ocultos, com opcoes para restaurar ou excluir definitivamente.
- Move midia para uma pasta informada dentro de Fotos ou Videos.

## Como compilar

No Windows, como o projeto esta em uma pasta de rede, use `pushd` para mapear temporariamente a pasta:

```bat
pushd "\\10.75.2.4\seafi$\gustavoborges\Desktop\Galeria Android"
gradlew.bat assembleDebug
```

O APK debug fica em:

```text
app\build\outputs\apk\debug\app-debug.apk
```

## Proximos passos sugeridos

- Adicionar selecao multipla para excluir, mover e ocultar varios itens.
- Criar abas por pastas/album.
- Melhorar o visual da tela de detalhe com gestos de zoom e navegacao entre fotos.
- Adicionar protecao por senha/biometria na area de ocultos.

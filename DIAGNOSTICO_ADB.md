# Diagnóstico por cabo USB (ADB) — Keep Policial 3.2

O app escreve no **Logcat** com a tag **`KeepPolicial`**. O registro **nunca contém dados de pessoas**
(nome, CPF, RG, CNH, placa, endereço, senha, token): só componente, evento, contagens e tipo de erro.

## Preparar o celular
1. Configurações > Sobre o telefone > toque 7 vezes em **Número da versão** (ativa o modo desenvolvedor).
2. Configurações > Sistema > Opções do desenvolvedor > ligue **Depuração USB**.
3. Conecte o cabo, aceite a chave RSA no celular e confira no computador:

```bash
adb devices
```

## Ver o registro ao vivo

```bash
adb logcat -s KeepPolicial:V
```

Só avisos e erros:

```bash
adb logcat -s KeepPolicial:W
```

Salvar para enviar ao suporte (feche com Ctrl+C depois do teste):

```bash
adb logcat -s KeepPolicial:V > keeppolicial-log.txt
```

## Instalar o APK de teste

```bash
adb install -r KeepPolicial-3.2.apk
```

## O que cada componente registra

| Componente | Eventos |
|---|---|
| `App` | abertura de cada tela, versão, Android, sessão online/offline |
| `Camera` | câmera aberta, aplicativo de câmera ausente, **permissão negada**, câmera que não devolveu a foto, memória insuficiente |
| `OCR` | luminosidade da foto (**escura**), uso do realce, nº de caracteres lidos, tempo, falhas do motor |
| `Armazem` | banco local: **disco cheio**, arquivo corrompido, dados ilegíveis descartados, itens enfileirados |
| `Cofre` | falha ao cifrar/decifrar (chave do Keystore invalidada, dado corrompido) |
| `Sync` | rodadas de sincronização, **conflitos de edição**, alterações recusadas pelo servidor (HTTP 4xx), foto perdida |
| `Repositorio` | falha ao salvar uma ficha no aparelho |
| `CRASH` | exceção não tratada (também vai ao servidor, na aba **Falhas** do painel) |

## Diagnóstico dentro do app
Menu (⋮) > **Diagnóstico (registro de eventos)** mostra versão, rede, fichas no aparelho, pendências, último
erro de sincronização e as últimas linhas do registro, com botão **Copiar**.

## Testes físicos sugeridos
- **Câmera negada / sem app de câmera:** o app mostra aviso e continua; confira `Camera` no log.
- **Pouca luz:** escaneie um documento em ambiente escuro; o log mostra `luz=... (escura)` e o realce.
- **Offline:** ative o modo avião, salve uma ficha, desative e acompanhe `Sync`.
- **Conflito:** edite a mesma ficha no celular (offline) e no painel/outro aparelho; ao sincronizar aparece `CONFLITO`.
- **Disco cheio:** encha o armazenamento e salve; o app deve avisar sem fechar (`Armazem ... SEM ESPACO`).

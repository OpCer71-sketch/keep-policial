# Keep Policial

Bloco de anotações para controle de indivíduos contumazes, de **uso interno da corporação**.
As fichas (dados, passagens, veículos e fotos) ficam somente no servidor do destacamento. O celular consulta e não guarda nada.

**Versão atual: 3.0** (servidor 3.0.0, app 3.0)

## Componentes

| Pasta | O que é |
|---|---|
| `servidor/` | Servidor em Python 3, sem dependências externas, para Ubuntu. Inclui a API HTTPS, o painel do administrador, o instalador (`instalar.sh`) e o script de acesso pela internet (`ligar-internet.sh`, usa Tailscale Funnel). |
| `android/` | Aplicativo Android 7 ou mais recente, em Java. |
| `COMO_INSTALAR.md` | Instalação, uso e manutenção. |
| `ATUALIZAR_SERVIDOR_3.0.md` | Passo a passo simples para atualizar o servidor da 2.0 para a 3.0. |

**Baixar tudo pronto (servidor, APK e manuais):** [KeepPolicial-instalacao.zip](https://github.com/OpCer71-sketch/keep-policial/releases/latest/download/KeepPolicial-instalacao.zip). Abra o `LEIA-ME PRIMEIRO.txt` que vem dentro.

O APK sozinho fica em [Releases](../../releases).

## Funcionalidades

- **Fichas:**
  - até 10 fotos;
  - nome, alcunha, CPF, RG, endereço e filiação;
  - comportamento: obediente, resistente passivo ou resistente ativo;
  - passagens (REDS);
  - veículos: marca, modelo, cor e placa;
  - observações.
- **Busca** por nome, alcunha, CPF, RG, mãe, REDS, placa ou modelo.
- **Importação** de fichas a partir de planilha (`.xlsx`, `.csv`) ou JSON, sem duplicar pessoas já cadastradas. Disponível só para o administrador.
- **Login no formato `usuario@servidor`.** O código de verificação do certificado é conferido só no primeiro acesso, e a conexão é sempre HTTPS com fixação de certificado.
- **Sessão de 10 horas**, guardada cifrada no Android Keystore.
- **Termo de compromisso de sigilo** aceito a cada login.
- **Atualização automática (3.0):** o app e o servidor consultam as Releases deste repositório uma vez por dia e baixam a versão nova.
  - No app, aparece o pedido de instalação.
  - No servidor, o administrador clica em **Instalar agora** no painel. Se a versão nova falhar, a anterior volta sozinha.
- **Avisos do comando (3.0):** o administrador publica um aviso no painel. Cada policial vê o pop-up ao abrir o app e confirma com **Ciente**. A ciência fica registrada.
- **Painel do administrador** em `http://127.0.0.1:8480`, que só abre no próprio servidor. Nele se gerenciam usuários e senhas e se consulta a auditoria.
- **Segurança:**
  - bloqueio de senhas erradas por usuário e por IP;
  - auditoria de todos os acessos;
  - lê o IP real dos celulares que chegam pelo Tailscale Funnel (PROXY protocol).

## Compilar o app

É preciso ter o JDK 17 e o Android SDK 34. Depois, rode:

```bash
cd android
gradle assembleRelease
```

## Aviso

Este software é de uso interno. Não publique dados reais de fichas, nem os arquivos `/var/lib/keeppolicial`, backups ou certificados do servidor.

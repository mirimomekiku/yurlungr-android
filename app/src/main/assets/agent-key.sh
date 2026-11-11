#!/usr/bin/env bash
# Saves an API key where an agent reads it, readable only by you (Android has no keyring, so
# these agents' own sign-in can't keep a token):
#
#   bash agent-key.sh vibe         Mistral Vibe   -> ~/.vibe/.env (MISTRAL_API_KEY)
#   bash agent-key.sh fast-agent   fast-agent     -> ~/.config/yurlungr/fast-agent.env (its wrapper loads it)
#   bash agent-key.sh minion       Minion Code    -> ~/.minion/config.yaml (an OpenAI-compatible model)
set -u
AGENT="${1:-}"
umask 077

ask() { # PROMPT DEFAULT: the answer, or DEFAULT when left empty
  local v
  if [ -n "$2" ]; then printf '%s [%s]: ' "$1" "$2"; else printf '%s: ' "$1"; fi
  IFS= read -r v || exit 1
  REPLY=${v:-$2}
}
ask_key() { # PROMPT: a key, typed without echo; no spaces or quotes
  local v
  printf '%s (hidden while you type or paste): ' "$1"
  IFS= read -rs v || exit 1
  echo
  v=$(printf '%s' "$v" | tr -d '[:space:]')
  case "$v" in
    "") echo "No key entered; nothing changed."; exit 1 ;;
    *[\'\"\\\`\$]*) echo "That doesn't look like an API key (it has quotes or symbols); nothing changed."; exit 1 ;;
  esac
  REPLY=$v
}
save() { # FILE: stdin replaces FILE (owner-only), the old one kept as FILE.bak
  mkdir -p "$(dirname "$1")" || exit 73
  cat > "$1.new" && chmod 600 "$1.new" || { rm -f "$1.new"; echo "Couldn't write $1"; exit 73; }
  [ -f "$1" ] && cp -p "$1" "$1.bak"
  mv -f "$1.new" "$1" && printf '\n\033[1;32mSaved to %s.\033[0m Start a new chat to use it.\n' "$1"
}

case "$AGENT" in
  vibe)
    echo "Mistral Vibe uses a Mistral API key: create one at https://console.mistral.ai/api-keys"
    echo "(a Le Chat Pro plan includes Vibe usage)."
    ask_key "Mistral API key"
    f="$HOME/.vibe/.env"
    { [ -f "$f" ] && grep -v '^MISTRAL_API_KEY=' "$f"; printf 'MISTRAL_API_KEY=%s\n' "$REPLY"; } | save "$f"
    ;;
  fast-agent)
    echo "fast-agent works with your own provider key. Which provider?"
    echo "  1) Anthropic   2) OpenAI   3) Google Gemini   4) OpenRouter"
    ask "Provider" 1
    case "$REPLY" in
      1) var=ANTHROPIC_API_KEY; model=sonnet ;;
      2) var=OPENAI_API_KEY; model=gpt-5 ;;
      3) var=GOOGLE_API_KEY; model=gemini25pro ;;
      4) var=OPENROUTER_API_KEY; model=openrouter.anthropic/claude-sonnet-4.5 ;;
      *) echo "Pick 1 to 4; nothing changed."; exit 1 ;;
    esac
    ask_key "$var"
    key=$REPLY
    echo "Model, in fast-agent's notation (an alias like sonnet, or provider.model):"
    ask "Model" "$model"
    case "$REPLY" in *[\'\"\\\`\$[:space:]]*) echo "Invalid model name; nothing changed."; exit 1 ;; esac
    printf "export %s='%s'\nexport FAST_AGENT_MODEL='%s'\n" "$var" "$key" "$REPLY" | save "$HOME/.config/yurlungr/fast-agent.env"
    ;;
  minion)
    echo "Minion Code talks to any OpenAI-compatible endpoint (OpenAI, OpenRouter, DeepSeek, a local server...)."
    ask "Base URL" "https://api.openai.com/v1"
    url=$REPLY
    ask_key "API key"
    key=$REPLY
    ask "Model" "gpt-5.1"
    model=$REPLY
    for v in "$url" "$model"; do
      case "$v" in *[\'\"\\\`\$[:space:]]*) echo "Invalid value '$v'; nothing changed."; exit 1 ;; esac
    done
    printf 'models:\n  "default":\n    api_type: "openai"\n    base_url: "%s"\n    api_key: "%s"\n    model: "%s"\n' "$url" "$key" "$model" \
      | save "$HOME/.minion/config.yaml"
    ;;
  *) printf 'usage: agent-key.sh vibe|fast-agent|minion\n' >&2; exit 64 ;;
esac

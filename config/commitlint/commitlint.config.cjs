const standardRules = {
  "header-max-length": [2, "always", 72],
  "subject-empty": [2, "never"],
  "subject-case": [2, "always", "lower-case"],
  "subject-full-stop": [2, "never", "."],
  "type-enum": [2, "always", ["feat", "fix", "refactor", "perf", "test", "docs", "build", "ci", "chore", "revert"]],
  "scope-enum": [2, "always", ["local", "gradle", "build", "ci", "docs", "repo", "test"]]
};

module.exports = {
  extends: ["@commitlint/config-conventional"],
  plugins: [{ rules: { "no-ai-attribution": ({ raw }) => [
    !/(co-authored-by|generated (?:by|with)|assisted-by):?.*(?:claude|codex|openai|anthropic)/i.test(raw),
    "commit messages must not contain AI attribution"
  ] } }],
  rules: { ...standardRules, "no-ai-attribution": [2, "always"] }
};

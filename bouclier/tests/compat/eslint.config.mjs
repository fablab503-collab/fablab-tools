// Safari 16.4 is Bouclier's oldest supported Safari (manifest strict_min_version).
import compat from 'eslint-plugin-compat';
import globals from 'globals';

export default [
  {
    files: ['**/*.js'],
    languageOptions: {
      ecmaVersion: 2022,
      sourceType: 'script',
      globals: { ...globals.browser, ...globals.serviceworker, chrome: 'readonly', browser: 'readonly', webkit: 'readonly' },
    },
    plugins: { compat },
    settings: { lintAllEsApis: true, browsers: ['safari >= 16.4', 'ios_saf >= 16.4'] },
    rules: {
      'compat/compat': 'error',
      'no-undef': 'error',
      'no-unused-vars': ['warn', { args: 'none', caughtErrors: 'none' }],
      'no-unreachable': 'error',
      'no-dupe-keys': 'error',
      'no-duplicate-case': 'error',
      'no-self-assign': 'error',
      'no-cond-assign': ['error', 'except-parens'],
      'no-constant-condition': ['error', { checkLoops: false }],
      'use-isnan': 'error',
      'valid-typeof': 'error',
    },
  },
];

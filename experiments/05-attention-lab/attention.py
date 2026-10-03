"""Inspectable attention mechanics, NOT a pretrained language model."""
import numpy as np

class Decoder:
    def __init__(self, seed=7, d=16, layers=2, vocab=32):
        rng = np.random.default_rng(seed)
        self.d, self.layers = d, layers
        self.embedding = rng.normal(0, .3, (vocab, d))
        self.weights = [[rng.normal(0, .25, (d, d)) for _ in range(3)] for _ in range(layers)]
        self.reset()

    def reset(self):
        self.projected_rows = self.score_elements = 0

    def inputs(self, tokens, positions):
        tokens, positions = np.asarray(tokens), np.asarray(positions)
        if tokens.ndim != 1 or len(tokens) == 0 or positions.shape != tokens.shape:
            raise ValueError('nonempty aligned tokens and positions required')
        if not np.issubdtype(tokens.dtype, np.integer) or np.any(tokens < 0) or np.any(tokens >= len(self.embedding)):
            raise ValueError('token ID out of vocabulary')
        if not np.issubdtype(positions.dtype, np.integer) or np.any(positions < 0) or np.any(np.diff(positions) != 1):
            raise ValueError('contiguous nonnegative absolute positions required')
        angle = positions[:, None] / (10000 ** (np.arange(0, self.d, 2) / self.d))
        p = np.empty((len(tokens), self.d))
        p[:, 0::2], p[:, 1::2] = np.sin(angle), np.cos(angle)
        return self.embedding[tokens] + p

    def attend(self, q, k, v, qp, kp, causal=True, window=None):
        if window is not None and (type(window) is not int or window < 1):
            raise ValueError('window must be positive integer')
        scores = q @ k.T / np.sqrt(self.d)
        self.score_elements += scores.size
        allowed = np.ones(scores.shape, dtype=bool)
        if causal:
            allowed &= kp[None, :] <= qp[:, None]
        if window is not None:
            allowed &= kp[None, :] > qp[:, None] - window
        if not allowed.any(axis=1).all():
            raise ValueError('all-masked query row')
        scores = np.where(allowed, scores, -np.inf)
        ex = np.exp(scores - scores.max(axis=1, keepdims=True))
        probs = ex / ex.sum(axis=1, keepdims=True)
        return probs @ v, probs

    def full(self, tokens, causal=True, window=None, offset=0):
        positions = np.arange(offset, offset + len(tokens))
        x = self.inputs(tokens, positions)
        probabilities = []
        for wq, wk, wv in self.weights:
            self.projected_rows += 3 * len(tokens)
            out, probs = self.attend(x @ wq, x @ wk, x @ wv, positions, positions, causal, window)
            x = np.tanh(x + out)
            probabilities.append(probs)
        return x, probabilities

    def chunk(self, tokens, cache=None, window=None, wrong_mask=False, reset_position=False):
        past = 0 if cache is None else cache['next_position']
        positions = np.arange(past, past + len(tokens))
        x = self.inputs(tokens, np.arange(len(tokens)) if reset_position else positions)
        new_layers = []
        for index, (wq, wk, wv) in enumerate(self.weights):
            self.projected_rows += 3 * len(tokens)
            q, k, v = x @ wq, x @ wk, x @ wv
            kp = positions
            if cache is not None:
                old_k, old_v, old_p = cache['layers'][index]
                k, v, kp = np.concatenate((old_k, k)), np.concatenate((old_v, v)), np.concatenate((old_p, positions))
            # The negative control incorrectly numbers query rows from zero.
            qp = np.arange(len(tokens)) if wrong_mask else positions
            out, _ = self.attend(q, k, v, qp, kp, True, window)
            x = np.tanh(x + out)
            keep = slice(None) if window is None else slice(-window, None)
            new_layers.append((k[keep].copy(), v[keep].copy(), kp[keep].copy()))
        return x, {'next_position':past + len(tokens), 'layers':new_layers}

def kv_bytes(cache):
    return sum(k.nbytes + v.nbytes for k, v, _ in cache['layers'])

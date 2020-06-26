import pytest

from hail.linalg import BlockMatrix
from hail.utils import new_temp_file, new_local_temp_dir, local_path_uri, FatalError
from ..helpers import *
import numpy as np
import tempfile
import math
from hail.expr.expressions import ExpressionException

setUpModule = startTestHailContext
tearDownModule = stopTestHailContext


def sparsify_numpy(np_mat, block_size, blocks_to_sparsify):
    n_rows, n_cols = np_mat.shape
    target_mat = np.zeros((n_rows, n_cols))
    n_block_rows = math.ceil(n_rows / block_size)
    n_block_cols = math.ceil(n_cols / block_size)
    n_rows_last_block = block_size if (n_rows % block_size) == 0 else n_rows % block_size
    n_cols_last_block = block_size if (n_cols % block_size) == 0 else n_cols % block_size

    for block in blocks_to_sparsify:
        block_row_idx = block % n_block_rows
        block_col_idx = block // n_block_rows
        rows_to_copy = block_size if block_row_idx != (n_block_rows - 1) else n_rows_last_block
        cols_to_copy = block_size if block_col_idx != (n_block_cols - 1) else n_cols_last_block
        starting_row_idx = block_row_idx * block_size
        starting_col_idx = block_col_idx * block_size

        a = starting_row_idx
        b = starting_row_idx + rows_to_copy
        c = starting_col_idx
        d = starting_col_idx + cols_to_copy
        target_mat[a:b, c:d] = np_mat[a:b, c:d]

    return target_mat


class Tests(unittest.TestCase):
    @staticmethod
    def _np_matrix(a):
        if isinstance(a, BlockMatrix):
            return np.array(a.to_numpy())
        else:
            return np.array(a)

    def _assert_eq(self, a, b):
        self.assertTrue(np.array_equal(self._np_matrix(a), self._np_matrix(b)))

    def _assert_close(self, a, b):
        self.assertTrue(np.allclose(self._np_matrix(a), self._np_matrix(b)))

    def _assert_rectangles_eq(self, expected, rect_path, export_rects, binary=False):
        for (i, r) in enumerate(export_rects):
            file = rect_path + '/rect-' + str(i) + '_' + '-'.join(map(str, r))
            expected_rect = expected[r[0]:r[1], r[2]:r[3]]
            actual_rect = np.reshape(np.fromfile(file), (r[1] - r[0], r[3] - r[2])) if binary else np.loadtxt(file, ndmin=2)
            self._assert_eq(expected_rect, actual_rect)

    def assert_sums_agree(self, bm, nd):
        self.assertAlmostEqual(bm.sum(), np.sum(nd))
        self._assert_close(bm.sum(axis=0), np.sum(nd, axis=0, keepdims=True))
        self._assert_close(bm.sum(axis=1), np.sum(nd, axis=1, keepdims=True))

    def test_from_entry_expr(self):
        mt = get_dataset()
        mt = mt.annotate_entries(x=hl.or_else(mt.GT.n_alt_alleles(), 0)).cache()

        a1 = BlockMatrix.from_entry_expr(hl.or_else(mt.GT.n_alt_alleles(), 0), block_size=32).to_numpy()
        a2 = BlockMatrix.from_entry_expr(mt.x, block_size=32).to_numpy()
        a3 = BlockMatrix.from_entry_expr(hl.float64(mt.x), block_size=32).to_numpy()

        self._assert_eq(a1, a2)
        self._assert_eq(a1, a3)

        path = new_temp_file()
        BlockMatrix.write_from_entry_expr(mt.x, path, block_size=32)
        a4 = BlockMatrix.read(path).to_numpy()
        self._assert_eq(a1, a4)

    def test_from_entry_expr_options(self):
        def build_mt(a):
            data = [{'v': 0, 's': 0, 'x': a[0]},
                    {'v': 0, 's': 1, 'x': a[1]},
                    {'v': 0, 's': 2, 'x': a[2]}]
            ht = hl.Table.parallelize(data, hl.dtype('struct{v: int32, s: int32, x: float64}'))
            mt = ht.to_matrix_table(['v'], ['s'])
            ids = mt.key_cols_by()['s'].collect()
            return mt.choose_cols([ids.index(0), ids.index(1), ids.index(2)])

        def check(expr, mean_impute, center, normalize, expected):
            actual = np.squeeze(BlockMatrix.from_entry_expr(expr,
                                                            mean_impute=mean_impute,
                                                            center=center,
                                                            normalize=normalize).to_numpy())
            assert np.allclose(actual, expected)

        a = np.array([0.0, 1.0, 2.0])

        mt = build_mt(a)
        check(mt.x, False, False, False, a)
        check(mt.x, False, True, False, a - 1.0)
        check(mt.x, False, False, True, a / np.sqrt(5))
        check(mt.x, False, True, True, (a - 1.0) / np.sqrt(2))
        check(mt.x + 1 - 1, False, False, False, a)

        mt = build_mt([0.0, hl.null('float64'), 2.0])
        check(mt.x, True, False, False, a)
        check(mt.x, True, True, False, a - 1.0)
        check(mt.x, True, False, True, a / np.sqrt(5))
        check(mt.x, True, True, True, (a - 1.0) / np.sqrt(2))
        with self.assertRaises(Exception):
            BlockMatrix.from_entry_expr(mt.x)

    def test_write_from_entry_expr_overwrite(self):
        mt = hl.balding_nichols_model(1, 1, 1)
        mt = mt.select_entries(x=mt.GT.n_alt_alleles())
        bm = BlockMatrix.from_entry_expr(mt.x)

        path = new_temp_file()
        BlockMatrix.write_from_entry_expr(mt.x, path)
        self.assertRaises(FatalError, lambda: BlockMatrix.write_from_entry_expr(mt.x, path))

        BlockMatrix.write_from_entry_expr(mt.x, path, overwrite=True)
        self._assert_eq(BlockMatrix.read(path), bm)

        # non-field expressions currently take a separate code path
        path2 = new_temp_file()
        BlockMatrix.write_from_entry_expr(mt.x + 1, path2)
        self.assertRaises(FatalError, lambda: BlockMatrix.write_from_entry_expr(mt.x + 1, path2))

        BlockMatrix.write_from_entry_expr(mt.x + 2, path2, overwrite=True)
        self._assert_eq(BlockMatrix.read(path2), bm + 2)

    def test_random_uniform(self):
        uniform = BlockMatrix.random(10, 10, gaussian=False)

        nuniform = uniform.to_numpy()
        for row in nuniform:
            for entry in row:
                assert entry > 0

    def test_to_from_numpy(self):
        n_rows = 10
        n_cols = 11
        data = np.random.rand(n_rows * n_cols)

        bm = BlockMatrix._create(n_rows, n_cols, data.tolist(), block_size=4)
        a = data.reshape((n_rows, n_cols))

        with tempfile.NamedTemporaryFile() as bm_f:
            with tempfile.NamedTemporaryFile() as a_f:
                bm.tofile(bm_f.name)
                a.tofile(a_f.name)

                a1 = bm.to_numpy()
                a2 = BlockMatrix.from_numpy(a, block_size=5).to_numpy()
                a3 = np.fromfile(bm_f.name).reshape((n_rows, n_cols))
                a4 = BlockMatrix.fromfile(a_f.name, n_rows, n_cols, block_size=3).to_numpy()
                a5 = BlockMatrix.fromfile(bm_f.name, n_rows, n_cols).to_numpy()

                self._assert_eq(a1, a)
                self._assert_eq(a2, a)
                self._assert_eq(a3, a)
                self._assert_eq(a4, a)
                self._assert_eq(a5, a)

        bmt = bm.T
        at = a.T

        with tempfile.NamedTemporaryFile() as bmt_f:
            with tempfile.NamedTemporaryFile() as at_f:
                bmt.tofile(bmt_f.name)
                at.tofile(at_f.name)

                at1 = bmt.to_numpy()
                at2 = BlockMatrix.from_numpy(at).to_numpy()
                at3 = np.fromfile(bmt_f.name).reshape((n_cols, n_rows))
                at4 = BlockMatrix.fromfile(at_f.name, n_cols, n_rows).to_numpy()
                at5 = BlockMatrix.fromfile(bmt_f.name, n_cols, n_rows).to_numpy()

                self._assert_eq(at1, at)
                self._assert_eq(at2, at)
                self._assert_eq(at3, at)
                self._assert_eq(at4, at)
                self._assert_eq(at5, at)

        self._assert_eq(bm.to_numpy(_force_blocking=True), a)

    def test_to_table(self):
        schema = hl.tstruct(row_idx=hl.tint64, entries=hl.tarray(hl.tfloat64))
        rows = [{'row_idx': 0, 'entries': [0.0, 1.0]},
                {'row_idx': 1, 'entries': [2.0, 3.0]},
                {'row_idx': 2, 'entries': [4.0, 5.0]},
                {'row_idx': 3, 'entries': [6.0, 7.0]},
                {'row_idx': 4, 'entries': [8.0, 9.0]}]

        for n_partitions in [1, 2, 3]:
            for block_size in [1, 2, 5]:
                expected = hl.Table.parallelize(rows, schema, 'row_idx', n_partitions)
                bm = BlockMatrix._create(5, 2, [float(i) for i in range(10)], block_size)
                actual = bm.to_table_row_major(n_partitions)
                self.assertTrue(expected._same(actual))

    def test_to_matrix_table(self):
        n_partitions = 2
        rows, cols = 2, 5
        bm = BlockMatrix._create(rows, cols, [float(i) for i in range(10)])
        actual = bm.to_matrix_table_row_major(n_partitions)

        expected = hl.utils.range_matrix_table(rows, cols)
        expected = expected.annotate_entries(element=hl.float64(expected.row_idx * cols + expected.col_idx))
        expected = expected.key_cols_by(col_idx=hl.int64(expected.col_idx))
        expected = expected.key_rows_by(row_idx=hl.int64(expected.row_idx))
        assert expected._same(actual)

        bm = BlockMatrix.random(50, 100, block_size=25, seed=0)
        mt = bm.to_matrix_table_row_major(n_partitions)
        mt_round_trip = BlockMatrix.from_entry_expr(mt.element).to_matrix_table_row_major()
        assert mt._same(mt_round_trip)

    def test_elementwise_ops(self):
        nx = np.array([[2.0]])
        nc = np.array([[1.0], [2.0]])
        nr = np.array([[1.0, 2.0, 3.0]])
        nm = np.array([[1.0, 2.0, 3.0], [4.0, 5.0, 6.0]])

        e = 2.0
        x = BlockMatrix.from_numpy(nx, block_size=8)
        c = BlockMatrix.from_numpy(nc, block_size=8)
        r = BlockMatrix.from_numpy(nr, block_size=8)
        m = BlockMatrix.from_numpy(nm, block_size=8)

        self.assertRaises(TypeError,
                          lambda: x + np.array(['one'], dtype=str))

        self._assert_eq(+m, 0 + m)
        self._assert_eq(-m, 0 - m)

        # addition
        self._assert_eq(x + e, nx + e)
        self._assert_eq(c + e, nc + e)
        self._assert_eq(r + e, nr + e)
        self._assert_eq(m + e, nm + e)

        self._assert_eq(x + e, e + x)
        self._assert_eq(c + e, e + c)
        self._assert_eq(r + e, e + r)
        self._assert_eq(m + e, e + m)

        self._assert_eq(x + x, 2 * x)
        self._assert_eq(c + c, 2 * c)
        self._assert_eq(r + r, 2 * r)
        self._assert_eq(m + m, 2 * m)

        self._assert_eq(x + c, np.array([[3.0], [4.0]]))
        self._assert_eq(x + r, np.array([[3.0, 4.0, 5.0]]))
        self._assert_eq(x + m, np.array([[3.0, 4.0, 5.0], [6.0, 7.0, 8.0]]))
        self._assert_eq(c + m, np.array([[2.0, 3.0, 4.0], [6.0, 7.0, 8.0]]))
        self._assert_eq(r + m, np.array([[2.0, 4.0, 6.0], [5.0, 7.0, 9.0]]))
        self._assert_eq(x + c, c + x)
        self._assert_eq(x + r, r + x)
        self._assert_eq(x + m, m + x)
        self._assert_eq(c + m, m + c)
        self._assert_eq(r + m, m + r)

        self._assert_eq(x + nx, x + x)
        self._assert_eq(x + nc, x + c)
        self._assert_eq(x + nr, x + r)
        self._assert_eq(x + nm, x + m)
        self._assert_eq(c + nx, c + x)
        self._assert_eq(c + nc, c + c)
        self._assert_eq(c + nm, c + m)
        self._assert_eq(r + nx, r + x)
        self._assert_eq(r + nr, r + r)
        self._assert_eq(r + nm, r + m)
        self._assert_eq(m + nx, m + x)
        self._assert_eq(m + nc, m + c)
        self._assert_eq(m + nr, m + r)
        self._assert_eq(m + nm, m + m)

        # subtraction
        self._assert_eq(x - e, nx - e)
        self._assert_eq(c - e, nc - e)
        self._assert_eq(r - e, nr - e)
        self._assert_eq(m - e, nm - e)

        self._assert_eq(x - e, -(e - x))
        self._assert_eq(c - e, -(e - c))
        self._assert_eq(r - e, -(e - r))
        self._assert_eq(m - e, -(e - m))

        self._assert_eq(x - x, np.zeros((1, 1)))
        self._assert_eq(c - c, np.zeros((2, 1)))
        self._assert_eq(r - r, np.zeros((1, 3)))
        self._assert_eq(m - m, np.zeros((2, 3)))

        self._assert_eq(x - c, np.array([[1.0], [0.0]]))
        self._assert_eq(x - r, np.array([[1.0, 0.0, -1.0]]))
        self._assert_eq(x - m, np.array([[1.0, 0.0, -1.0], [-2.0, -3.0, -4.0]]))
        self._assert_eq(c - m, np.array([[0.0, -1.0, -2.0], [-2.0, -3.0, -4.0]]))
        self._assert_eq(r - m, np.array([[0.0, 0.0, 0.0], [-3.0, -3.0, -3.0]]))
        self._assert_eq(x - c, -(c - x))
        self._assert_eq(x - r, -(r - x))
        self._assert_eq(x - m, -(m - x))
        self._assert_eq(c - m, -(m - c))
        self._assert_eq(r - m, -(m - r))

        self._assert_eq(x - nx, x - x)
        self._assert_eq(x - nc, x - c)
        self._assert_eq(x - nr, x - r)
        self._assert_eq(x - nm, x - m)
        self._assert_eq(c - nx, c - x)
        self._assert_eq(c - nc, c - c)
        self._assert_eq(c - nm, c - m)
        self._assert_eq(r - nx, r - x)
        self._assert_eq(r - nr, r - r)
        self._assert_eq(r - nm, r - m)
        self._assert_eq(m - nx, m - x)
        self._assert_eq(m - nc, m - c)
        self._assert_eq(m - nr, m - r)
        self._assert_eq(m - nm, m - m)

        # multiplication
        self._assert_eq(x * e, nx * e)
        self._assert_eq(c * e, nc * e)
        self._assert_eq(r * e, nr * e)
        self._assert_eq(m * e, nm * e)

        self._assert_eq(x * e, e * x)
        self._assert_eq(c * e, e * c)
        self._assert_eq(r * e, e * r)
        self._assert_eq(m * e, e * m)

        self._assert_eq(x * x, x ** 2)
        self._assert_eq(c * c, c ** 2)
        self._assert_eq(r * r, r ** 2)
        self._assert_eq(m * m, m ** 2)

        self._assert_eq(x * c, np.array([[2.0], [4.0]]))
        self._assert_eq(x * r, np.array([[2.0, 4.0, 6.0]]))
        self._assert_eq(x * m, np.array([[2.0, 4.0, 6.0], [8.0, 10.0, 12.0]]))
        self._assert_eq(c * m, np.array([[1.0, 2.0, 3.0], [8.0, 10.0, 12.0]]))
        self._assert_eq(r * m, np.array([[1.0, 4.0, 9.0], [4.0, 10.0, 18.0]]))
        self._assert_eq(x * c, c * x)
        self._assert_eq(x * r, r * x)
        self._assert_eq(x * m, m * x)
        self._assert_eq(c * m, m * c)
        self._assert_eq(r * m, m * r)

        self._assert_eq(x * nx, x * x)
        self._assert_eq(x * nc, x * c)
        self._assert_eq(x * nr, x * r)
        self._assert_eq(x * nm, x * m)
        self._assert_eq(c * nx, c * x)
        self._assert_eq(c * nc, c * c)
        self._assert_eq(c * nm, c * m)
        self._assert_eq(r * nx, r * x)
        self._assert_eq(r * nr, r * r)
        self._assert_eq(r * nm, r * m)
        self._assert_eq(m * nx, m * x)
        self._assert_eq(m * nc, m * c)
        self._assert_eq(m * nr, m * r)
        self._assert_eq(m * nm, m * m)

        # division
        self._assert_close(x / e, nx / e)
        self._assert_close(c / e, nc / e)
        self._assert_close(r / e, nr / e)
        self._assert_close(m / e, nm / e)

        self._assert_close(x / e, 1 / (e / x))
        self._assert_close(c / e, 1 / (e / c))
        self._assert_close(r / e, 1 / (e / r))
        self._assert_close(m / e, 1 / (e / m))

        self._assert_close(x / x, np.ones((1, 1)))
        self._assert_close(c / c, np.ones((2, 1)))
        self._assert_close(r / r, np.ones((1, 3)))
        self._assert_close(m / m, np.ones((2, 3)))

        self._assert_close(x / c, np.array([[2 / 1.0], [2 / 2.0]]))
        self._assert_close(x / r, np.array([[2 / 1.0, 2 / 2.0, 2 / 3.0]]))
        self._assert_close(x / m, np.array([[2 / 1.0, 2 / 2.0, 2 / 3.0], [2 / 4.0, 2 / 5.0, 2 / 6.0]]))
        self._assert_close(c / m, np.array([[1 / 1.0, 1 / 2.0, 1 / 3.0], [2 / 4.0, 2 / 5.0, 2 / 6.0]]))
        self._assert_close(r / m, np.array([[1 / 1.0, 2 / 2.0, 3 / 3.0], [1 / 4.0, 2 / 5.0, 3 / 6.0]]))
        self._assert_close(x / c, 1 / (c / x))
        self._assert_close(x / r, 1 / (r / x))
        self._assert_close(x / m, 1 / (m / x))
        self._assert_close(c / m, 1 / (m / c))
        self._assert_close(r / m, 1 / (m / r))

        self._assert_close(x / nx, x / x)
        self._assert_close(x / nc, x / c)
        self._assert_close(x / nr, x / r)
        self._assert_close(x / nm, x / m)
        self._assert_close(c / nx, c / x)
        self._assert_close(c / nc, c / c)
        self._assert_close(c / nm, c / m)
        self._assert_close(r / nx, r / x)
        self._assert_close(r / nr, r / r)
        self._assert_close(r / nm, r / m)
        self._assert_close(m / nx, m / x)
        self._assert_close(m / nc, m / c)
        self._assert_close(m / nr, m / r)
        self._assert_close(m / nm, m / m)

    def test_special_elementwise_ops(self):
        nm = np.array([[1.0, 2.0, 3.0, 3.14], [4.0, 5.0, 6.0, 12.12]])
        m = BlockMatrix.from_numpy(nm)

        self._assert_close(m ** 3, nm ** 3)
        self._assert_close(m.sqrt(), np.sqrt(nm))
        self._assert_close(m.ceil(), np.ceil(nm))
        self._assert_close(m.floor(), np.floor(nm))
        self._assert_close(m.log(), np.log(nm))
        self._assert_close((m - 4).abs(), np.abs(nm - 4))

    def test_matrix_ops(self):
        nm = np.array([[1.0, 2.0, 3.0], [4.0, 5.0, 6.0]])
        m = BlockMatrix.from_numpy(nm, block_size=2)
        nsquare = np.array([[1.0, 2.0, 3.0], [4.0, 5.0, 6.0], [7.0, 8.0, 9.0]])
        square = BlockMatrix.from_numpy(nsquare, block_size=2)

        nrow = np.array([[7.0, 8.0, 9.0]])
        row = BlockMatrix.from_numpy(nrow, block_size=2)

        self._assert_eq(m.T, nm.T)
        self._assert_eq(m.T, nm.T)
        self._assert_eq(row.T, nrow.T)

        self._assert_eq(m @ m.T, nm @ nm.T)
        self._assert_eq(m @ nm.T, nm @ nm.T)
        self._assert_eq(row @ row.T, nrow @ nrow.T)
        self._assert_eq(row @ nrow.T, nrow @ nrow.T)

        self._assert_eq(m.T @ m, nm.T @ nm)
        self._assert_eq(m.T @ nm, nm.T @ nm)
        self._assert_eq(row.T @ row, nrow.T @ nrow)
        self._assert_eq(row.T @ nrow, nrow.T @ nrow)

        self.assertRaises(ValueError, lambda: m @ m)
        self.assertRaises(ValueError, lambda: m @ nm)

        self._assert_eq(m.diagonal(), np.array([[1.0, 5.0]]))
        self._assert_eq(m.T.diagonal(), np.array([[1.0, 5.0]]))
        self._assert_eq((m @ m.T).diagonal(), np.array([[14.0, 77.0]]))

        self._assert_eq(m.sum(axis=0).T, np.array([[5.0], [7.0], [9.0]]))
        self._assert_eq(m.sum(axis=1).T, np.array([[6.0, 15.0]]))
        self._assert_eq(m.sum(axis=0).T + row, np.array([[12.0, 13.0, 14.0],
                                                         [14.0, 15.0, 16.0],
                                                         [16.0, 17.0, 18.0]]))
        self._assert_eq(m.sum(axis=0) + row.T, np.array([[12.0, 14.0, 16.0],
                                                         [13.0, 15.0, 17.0],
                                                         [14.0, 16.0, 18.0]]))
        self._assert_eq(square.sum(axis=0).T + square.sum(axis=1), np.array([[18.0], [30.0], [42.0]]))

    def test_fill(self):
        nd = np.ones((3, 5))
        bm = BlockMatrix.fill(3, 5, 1.0)
        bm2 = BlockMatrix.fill(3, 5, 1.0, block_size=2)

        self.assertTrue(bm.block_size == BlockMatrix.default_block_size())
        self.assertTrue(bm2.block_size == 2)
        self._assert_eq(bm, nd)
        self._assert_eq(bm2, nd)

    def test_sum(self):
        nd = np.random.normal(size=(11, 13))
        bm = BlockMatrix.from_numpy(nd, block_size=3)

        self.assert_sums_agree(bm, nd)

    @skip_unless_spark_backend()
    def test_sum_with_sparsify(self):
        nd = np.zeros(shape=(5, 7))
        nd[2, 4] = 1.0
        nd[2, 5] = 2.0
        nd[3, 4] = 3.0
        nd[3, 5] = 4.0
        bm = BlockMatrix.from_numpy(nd, block_size=2).sparsify_rectangles([[2, 4, 4, 6]])

        bm2 = BlockMatrix.from_numpy(nd, block_size=2).sparsify_rectangles([[2, 4, 4, 6], [0, 5, 0, 1]])

        bm3 = BlockMatrix.from_numpy(nd, block_size=2).sparsify_rectangles([[2, 4, 4, 6], [0, 1, 0, 7]])

        nd4 = np.zeros(shape=(5, 7))
        bm4 = BlockMatrix.fill(5, 7, value=0.0, block_size=2).sparsify_rectangles([])

        self.assert_sums_agree(bm, nd)
        self.assert_sums_agree(bm2, nd)
        self.assert_sums_agree(bm3, nd)
        self.assert_sums_agree(bm4, nd4)

    def test_slicing(self):
        nd = np.array(np.arange(0, 80, dtype=float)).reshape(8, 10)
        bm = BlockMatrix.from_numpy(nd, block_size=3)

        for indices in [(0, 0), (5, 7), (-3, 9), (-8, -10)]:
            self._assert_eq(bm[indices], nd[indices])

        for indices in [(0, slice(3, 4)),
                        (1, slice(3, 4)),
                        (-8, slice(3, 4)),
                        (-1, slice(3, 4))]:
            self._assert_eq(bm[indices], np.expand_dims(nd[indices], 0))
            self._assert_eq(bm[indices] - bm, nd[indices] - nd)
            self._assert_eq(bm - bm[indices], nd - nd[indices])

        for indices in [(slice(3, 4), 0),
                        (slice(3, 4), 1),
                        (slice(3, 4), -8),
                        (slice(3, 4), -1)]:
            self._assert_eq(bm[indices], np.expand_dims(nd[indices], 1))
            self._assert_eq(bm[indices] - bm, nd[indices] - nd)
            self._assert_eq(bm - bm[indices], nd - nd[indices])

        for indices in [(slice(0, 8), slice(0, 10)),
                        (slice(0, 8, 2), slice(0, 10, 2)),
                        (slice(2, 4), slice(5, 7)),
                        (slice(-8, -1), slice(-10, -1)),
                        (slice(-8, -1, 2), slice(-10, -1, 2)),
                        (slice(None, 4, 1), slice(None, 4, 1)),
                        (slice(4, None), slice(4, None)),
                        (slice(None, None), slice(None, None))]:
            self._assert_eq(bm[indices], nd[indices])
            self._assert_eq(bm[indices][:, :2], nd[indices][:, :2])
            self._assert_eq(bm[indices][:2, :], nd[indices][:2, :])

        self.assertRaises(ValueError, lambda: bm[0, ])

        self.assertRaises(ValueError, lambda: bm[9, 0])
        self.assertRaises(ValueError, lambda: bm[-9, 0])
        self.assertRaises(ValueError, lambda: bm[0, 11])
        self.assertRaises(ValueError, lambda: bm[0, -11])

        self.assertRaises(ValueError, lambda: bm[::-1, 0])
        self.assertRaises(ValueError, lambda: bm[0, ::-1])

        self.assertRaises(ValueError, lambda: bm[:0, 0])
        self.assertRaises(ValueError, lambda: bm[0, :0])

        self.assertRaises(ValueError, lambda: bm[0:9, 0])
        self.assertRaises(ValueError, lambda: bm[-9:, 0])
        self.assertRaises(ValueError, lambda: bm[:-9, 0])

        self.assertRaises(ValueError, lambda: bm[0, :11])
        self.assertRaises(ValueError, lambda: bm[0, -11:])
        self.assertRaises(ValueError, lambda: bm[0, :-11])

    @skip_unless_spark_backend()
    def test_slices_with_sparsify(self):
        nd = np.array(np.arange(0, 80, dtype=float)).reshape(8, 10)
        bm = BlockMatrix.from_numpy(nd, block_size=3)
        bm2 = bm.sparsify_row_intervals([0, 0, 0, 0, 0, 0, 0, 0], [2, 0, 0, 0, 0, 0, 0, 0])
        self.assertEqual(bm2[0, 1], 1.0)
        self.assertEqual(bm2[0, 2], 0.0)
        self.assertEqual(bm2[0, 9], 0.0)

        nd2 = np.zeros(shape=(8, 10))
        nd2[0, 1] = 1.0
        self._assert_eq(bm2[:, :], nd2)

        self._assert_eq(bm2[:, 1], nd2[:, 1:2])
        self._assert_eq(bm2[1, :], nd2[1:2, :])
        self._assert_eq(bm2[0:5, 0:5], nd2[0:5, 0:5])

    @skip_unless_spark_backend()
    def test_sparsify_row_intervals(self):
        nd = np.array([[ 1.0,  2.0,  3.0,  4.0],
                       [ 5.0,  6.0,  7.0,  8.0],
                       [ 9.0, 10.0, 11.0, 12.0],
                       [13.0, 14.0, 15.0, 16.0]])
        bm = BlockMatrix.from_numpy(nd, block_size=2)

        self._assert_eq(
            bm.sparsify_row_intervals(
                starts=[1, 0, 2, 2],
                stops= [2, 0, 3, 4]),
            np.array([[ 0.,  2.,  0.,  0.],
                      [ 0.,  0.,  0.,  0.],
                      [ 0.,  0., 11.,  0.],
                      [ 0.,  0., 15., 16.]]))

        self._assert_eq(
            bm.sparsify_row_intervals(
                starts=[1, 0, 2, 2],
                stops= [2, 0, 3, 4],
                blocks_only=True),
            np.array([[ 1.,  2.,  0.,  0.],
                      [ 5.,  6.,  0.,  0.],
                      [ 0.,  0., 11., 12.],
                      [ 0.,  0., 15., 16.]]))

        nd2 = np.random.normal(size=(8, 10))
        bm2 = BlockMatrix.from_numpy(nd2, block_size=3)

        for bounds in [[[0, 1, 2, 3, 4, 5, 6, 7],
                        [1, 2, 3, 4, 5, 6, 7, 8]],
                       [[0, 0, 5, 3, 4, 5, 8, 2],
                        [9, 0, 5, 3, 4, 5, 9, 5]],
                       [[0, 5, 10, 8, 7, 6, 5, 4],
                        [0, 5, 10, 9, 8, 7, 6, 5]]]:
            starts, stops = bounds
            actual = bm2.sparsify_row_intervals(starts, stops, blocks_only=False).to_numpy()
            expected = nd2.copy()
            for i in range(0, 8):
                for j in range(0, starts[i]):
                    expected[i, j] = 0.0
                for j in range(stops[i], 10):
                    expected[i, j] = 0.0
            self._assert_eq(actual, expected)

    @skip_unless_spark_backend()
    def test_sparsify_band(self):
        nd = np.array([[ 1.0,  2.0,  3.0,  4.0],
                       [ 5.0,  6.0,  7.0,  8.0],
                       [ 9.0, 10.0, 11.0, 12.0],
                       [13.0, 14.0, 15.0, 16.0]])
        bm = BlockMatrix.from_numpy(nd, block_size=2)

        self._assert_eq(
            bm.sparsify_band(lower=-1, upper=2),
            np.array([[ 1.,  2.,  3.,  0.],
                      [ 5.,  6.,  7.,  8.],
                      [ 0., 10., 11., 12.],
                      [ 0.,  0., 15., 16.]]))

        self._assert_eq(
            bm.sparsify_band(lower=0, upper=0, blocks_only=True),
            np.array([[ 1.,  2.,  0.,  0.],
                      [ 5.,  6.,  0.,  0.],
                      [ 0.,  0., 11., 12.],
                      [ 0.,  0., 15., 16.]]))

        nd2 = np.arange(0, 80, dtype=float).reshape(8, 10)
        bm2 = BlockMatrix.from_numpy(nd2, block_size=3)

        for bounds in [[0, 0], [1, 1], [2, 2], [-5, 5], [-7, 0], [0, 9], [-100, 100]]:
            lower, upper = bounds
            actual = bm2.sparsify_band(lower, upper, blocks_only=False).to_numpy()
            mask = np.fromfunction(lambda i, j: (lower <= j - i) * (j - i <= upper), (8, 10))
            self._assert_eq(actual, nd2 * mask)

    @skip_unless_spark_backend()
    def test_sparsify_triangle(self):
        nd = np.array([[ 1.0,  2.0,  3.0,  4.0],
                       [ 5.0,  6.0,  7.0,  8.0],
                       [ 9.0, 10.0, 11.0, 12.0],
                       [13.0, 14.0, 15.0, 16.0]])
        bm = BlockMatrix.from_numpy(nd, block_size=2)

        self.assertFalse(bm.is_sparse)
        self.assertTrue(bm.sparsify_triangle().is_sparse)

        self._assert_eq(
            bm.sparsify_triangle(),
            np.array([[ 1.,  2.,  3.,  4.],
                      [ 0.,  6.,  7.,  8.],
                      [ 0.,  0., 11., 12.],
                      [ 0.,  0.,  0., 16.]]))

        self._assert_eq(
            bm.sparsify_triangle(lower=True),
            np.array([[ 1.,  0.,  0.,  0.],
                      [ 5.,  6.,  0.,  0.],
                      [ 9., 10., 11.,  0.],
                      [13., 14., 15., 16.]]))

        self._assert_eq(
            bm.sparsify_triangle(blocks_only=True),
            np.array([[ 1.,  2.,  3.,  4.],
                      [ 5.,  6.,  7.,  8.],
                      [ 0.,  0., 11., 12.],
                      [ 0.,  0., 15., 16.]]))

    @skip_unless_spark_backend()
    def test_sparsify_rectangles(self):
        nd = np.array([[ 1.0,  2.0,  3.0,  4.0],
                       [ 5.0,  6.0,  7.0,  8.0],
                       [ 9.0, 10.0, 11.0, 12.0],
                       [13.0, 14.0, 15.0, 16.0]])
        bm = BlockMatrix.from_numpy(nd, block_size=2)

        self._assert_eq(
            bm.sparsify_rectangles([[0, 1, 0, 1], [0, 3, 0, 2], [1, 2, 0, 4]]),
            np.array([[ 1.,  2.,  3.,  4.],
                      [ 5.,  6.,  7.,  8.],
                      [ 9., 10.,  0.,  0.],
                      [13., 14.,  0.,  0.]]))

        self._assert_eq(bm.sparsify_rectangles([]), np.zeros(shape=(4, 4)))

    def test_export_rectangles(self):
        nd = np.arange(0, 80, dtype=float).reshape(8, 10)

        rects1 = [[0, 1, 0, 1], [4, 5, 7, 8]]

        rects2 = [[4, 5, 0, 10], [0, 8, 4, 5]]

        rects3 = [[0, 1, 0, 1], [1, 2, 1, 2], [2, 3, 2, 3],
                  [3, 5, 3, 6], [3, 6, 3, 7], [3, 7, 3, 8],
                  [4, 5, 0, 10], [0, 8, 4, 5], [0, 8, 0, 10]]

        for rects in [rects1, rects2, rects3]:
            for block_size in [3, 4, 10]:
                rect_path = new_local_temp_dir()
                rect_uri = local_path_uri(rect_path)

                bm = BlockMatrix.from_numpy(nd, block_size=block_size)
                bm.export_rectangles(rect_uri, rects)

                self._assert_rectangles_eq(nd, rect_path, rects)

                rect_path_bytes = new_local_temp_dir()
                rect_uri_bytes = local_path_uri(rect_path_bytes)

                bm.export_rectangles(rect_uri_bytes, rects, binary=True)
                self._assert_rectangles_eq(nd, rect_path_bytes, rects, binary=True)

    @skip_unless_spark_backend()
    def test_export_rectangles_sparse(self):
        rect_path = new_local_temp_dir()
        rect_uri = local_path_uri(rect_path)
        nd = np.array([[1.0, 2.0, 3.0, 4.0],
                       [5.0, 6.0, 7.0, 8.0],
                       [9.0, 10.0, 11.0, 12.0],
                       [13.0, 14.0, 15.0, 16.0]])
        bm = BlockMatrix.from_numpy(nd, block_size=2)
        sparsify_rects = [[0, 1, 0, 1], [0, 3, 0, 2], [1, 2, 0, 4]]
        export_rects = [[0, 1, 0, 1], [0, 3, 0, 2], [1, 2, 0, 4], [2, 4, 2, 4]]
        bm.sparsify_rectangles(sparsify_rects).export_rectangles(rect_uri, export_rects)

        expected = np.array([[1.0, 2.0, 3.0, 4.0],
                             [5.0, 6.0, 7.0, 8.0],
                             [9.0, 10.0, 0.0, 0.0],
                             [13.0, 14.0, 0.0, 0.0]])

        self._assert_rectangles_eq(expected, rect_path, export_rects)

    def test_export_rectangles_filtered(self):
        rect_path = new_local_temp_dir()
        rect_uri = local_path_uri(rect_path)
        nd = np.array([[1.0, 2.0, 3.0, 4.0],
                       [5.0, 6.0, 7.0, 8.0],
                       [9.0, 10.0, 11.0, 12.0],
                       [13.0, 14.0, 15.0, 16.0]])
        bm = BlockMatrix.from_numpy(nd)
        bm = bm[1:3, 1:3]
        export_rects = [[0, 1, 0, 2], [1, 2, 0, 2]]
        bm.export_rectangles(rect_uri, export_rects)

        expected = np.array([[6.0, 7.0],
                             [10.0, 11.0]])

        self._assert_rectangles_eq(expected, rect_path, export_rects)

    def test_export_blocks(self):
        nd = np.ones(shape=(8, 10))
        bm = BlockMatrix.from_numpy(nd, block_size=20)

        bm_path = new_local_temp_dir()
        bm_uri = local_path_uri(bm_path)
        bm.export_blocks(bm_uri, binary=True)
        actual = BlockMatrix.rectangles_to_numpy(bm_path, binary=True)

        self._assert_eq(nd, actual)

    def test_rectangles_to_numpy(self):
        nd = np.array([[1.0, 2.0, 3.0],
                       [4.0, 5.0, 6.0],
                       [7.0, 8.0, 9.0]])

        rects = [[0, 3, 0, 1], [1, 2, 0, 2]]

        rect_path = new_local_temp_dir()
        rect_uri = local_path_uri(rect_path)
        BlockMatrix.from_numpy(nd).export_rectangles(rect_uri, rects)

        rect_bytes_path = new_local_temp_dir()
        rect_bytes_uri = local_path_uri(rect_bytes_path)
        BlockMatrix.from_numpy(nd).export_rectangles(rect_bytes_uri, rects, binary=True)

        expected = np.array([[1.0, 0.0],
                             [4.0, 5.0],
                             [7.0, 0.0]])
        self._assert_eq(expected, BlockMatrix.rectangles_to_numpy(rect_path))
        self._assert_eq(expected, BlockMatrix.rectangles_to_numpy(rect_bytes_path, binary=True))

    def test_block_matrix_entries(self):
        n_rows, n_cols = 5, 3
        rows = [{'i': i, 'j': j, 'entry': float(i + j)} for i in range(n_rows) for j in range(n_cols)]
        schema = hl.tstruct(i=hl.tint32, j=hl.tint32, entry=hl.tfloat64)
        table = hl.Table.parallelize([hl.struct(i=row['i'], j=row['j'], entry=row['entry']) for row in rows], schema)
        table = table.annotate(i=hl.int64(table.i),
                               j=hl.int64(table.j)).key_by('i', 'j')

        ndarray = np.reshape(list(map(lambda row: row['entry'], rows)), (n_rows, n_cols))

        for block_size in [1, 2, 1024]:
            block_matrix = BlockMatrix.from_numpy(ndarray, block_size)
            entries_table = block_matrix.entries()
            self.assertEqual(entries_table.count(), n_cols * n_rows)
            self.assertEqual(len(entries_table.row), 3)
            self.assertTrue(table._same(entries_table))

    def test_from_entry_expr_filtered(self):
        mt = hl.utils.range_matrix_table(1, 1).filter_entries(False)
        bm = hl.linalg.BlockMatrix.from_entry_expr(mt.row_idx + mt.col_idx, mean_impute=True) # should run without error
        assert np.isnan(bm.entries().entry.collect()[0])

    def test_array_windows(self):
        def assert_eq(a, b):
            self.assertTrue(np.array_equal(a, np.array(b)))

        starts, stops = hl.linalg.utils.array_windows(np.array([1, 2, 4, 4, 6, 8]), 2)
        assert_eq(starts, [0, 0, 1, 1, 2, 4])
        assert_eq(stops, [2, 4, 5, 5, 6, 6])

        starts, stops = hl.linalg.utils.array_windows(np.array([-10.0, -2.5, 0.0, 0.0, 1.2, 2.3, 3.0]), 2.5)
        assert_eq(starts, [0, 1, 1, 1, 2, 2, 4])
        assert_eq(stops, [1, 4, 6, 6, 7, 7, 7])

        starts, stops = hl.linalg.utils.array_windows(np.array([0, 0, 1]), 0)
        assert_eq(starts, [0, 0, 2])
        assert_eq(stops, [2, 2, 3])

        starts, stops = hl.linalg.utils.array_windows(np.array([]), 1)
        self.assertEqual(starts.size, 0)
        self.assertEqual(stops.size, 0)

        starts, stops = hl.linalg.utils.array_windows(np.array([-float('inf'), -1, 0, 1, float("inf")]), 1)
        assert_eq(starts, [0, 1, 1, 2, 4])
        assert_eq(stops, [1, 3, 4, 4, 5])

        self.assertRaises(ValueError, lambda: hl.linalg.utils.array_windows(np.array([1, 0]), -1))
        self.assertRaises(ValueError, lambda: hl.linalg.utils.array_windows(np.array([0, float('nan')]), 1))
        self.assertRaises(ValueError, lambda: hl.linalg.utils.array_windows(np.array([float('nan')]), 1))
        self.assertRaises(ValueError, lambda: hl.linalg.utils.array_windows(np.array([0.0, float('nan')]), 1))
        self.assertRaises(ValueError, lambda: hl.linalg.utils.array_windows(np.array([None]), 1))
        self.assertRaises(ValueError, lambda: hl.linalg.utils.array_windows(np.array([]), -1))
        self.assertRaises(ValueError, lambda: hl.linalg.utils.array_windows(np.array(['str']), 1))

    def test_locus_windows(self):
        def assert_eq(a, b):
            self.assertTrue(np.array_equal(a, np.array(b)))

        centimorgans = hl.literal([0.1, 1.0, 1.0, 1.5, 1.9])

        mt = hl.balding_nichols_model(1, 5, 5).add_row_index()
        mt = mt.annotate_rows(cm=centimorgans[hl.int32(mt.row_idx)]).cache()

        starts, stops = hl.linalg.utils.locus_windows(mt.locus, 2)
        assert_eq(starts, [0, 0, 0, 1, 2])
        assert_eq(stops, [3, 4, 5, 5, 5])

        starts, stops = hl.linalg.utils.locus_windows(mt.locus, 0.5, coord_expr=mt.cm)
        assert_eq(starts, [0, 1, 1, 1, 3])
        assert_eq(stops, [1, 4, 4, 5, 5])

        starts, stops = hl.linalg.utils.locus_windows(mt.locus, 1.0, coord_expr=2 * centimorgans[hl.int32(mt.row_idx)])
        assert_eq(starts, [0, 1, 1, 1, 3])
        assert_eq(stops, [1, 4, 4, 5, 5])

        rows = [{'locus': hl.Locus('1', 1), 'cm': 1.0},
                {'locus': hl.Locus('1', 2), 'cm': 3.0},
                {'locus': hl.Locus('1', 4), 'cm': 4.0},
                {'locus': hl.Locus('2', 1), 'cm': 2.0},
                {'locus': hl.Locus('2', 1), 'cm': 2.0},
                {'locus': hl.Locus('3', 3), 'cm': 5.0}]

        ht = hl.Table.parallelize(rows,
                                  hl.tstruct(locus=hl.tlocus('GRCh37'), cm=hl.tfloat64),
                                  key=['locus'])

        starts, stops = hl.linalg.utils.locus_windows(ht.locus, 1)
        assert_eq(starts, [0, 0, 2, 3, 3, 5])
        assert_eq(stops, [2, 2, 3, 5, 5, 6])

        starts, stops = hl.linalg.utils.locus_windows(ht.locus, 1.0, coord_expr=ht.cm)
        assert_eq(starts, [0, 1, 1, 3, 3, 5])
        assert_eq(stops, [1, 3, 3, 5, 5, 6])

        with self.assertRaises(FatalError) as cm:
            hl.linalg.utils.locus_windows(ht.order_by(ht.cm).locus, 1.0)
        self.assertTrue('ascending order' in str(cm.exception))

        with self.assertRaises(ExpressionException) as cm:
            hl.linalg.utils.locus_windows(ht.locus, 1.0, coord_expr=hl.utils.range_table(1).idx)
        self.assertTrue('different source' in str(cm.exception))

        with self.assertRaises(ExpressionException) as cm:
            hl.linalg.utils.locus_windows(hl.locus('1', 1), 1.0)
        self.assertTrue("no source" in str(cm.exception))

        with self.assertRaises(ExpressionException) as cm:
            hl.linalg.utils.locus_windows(ht.locus, 1.0, coord_expr=0.0)
        self.assertTrue("no source" in str(cm.exception))

        ht = ht.annotate_globals(x = hl.locus('1', 1), y = 1.0)
        with self.assertRaises(ExpressionException) as cm:
            hl.linalg.utils.locus_windows(ht.x, 1.0)
        self.assertTrue("row-indexed" in str(cm.exception))
        with self.assertRaises(ExpressionException) as cm:
            hl.linalg.utils.locus_windows(ht.locus, 1.0, ht.y)
        self.assertTrue("row-indexed" in str(cm.exception))

        ht = hl.Table.parallelize([{'locus': hl.null(hl.tlocus()), 'cm': 1.0}],
                                  hl.tstruct(locus=hl.tlocus('GRCh37'), cm=hl.tfloat64), key=['locus'])
        with self.assertRaises(FatalError) as cm:
            hl.linalg.utils.locus_windows(ht.locus, 1.0)
        self.assertTrue("missing value for 'locus_expr'" in str(cm.exception))
        with self.assertRaises(FatalError) as cm:
            hl.linalg.utils.locus_windows(ht.locus, 1.0, coord_expr=ht.cm)
        self.assertTrue("missing value for 'locus_expr'" in str(cm.exception))

        ht = hl.Table.parallelize([{'locus': hl.Locus('1', 1), 'cm': hl.null(hl.tfloat64)}],
                                  hl.tstruct(locus=hl.tlocus('GRCh37'), cm=hl.tfloat64), key=['locus'])
        with self.assertRaises(FatalError) as cm:
            hl.linalg.utils.locus_windows(ht.locus, 1.0, coord_expr=ht.cm)
        self.assertTrue("missing value for 'coord_expr'" in str(cm.exception))

    def test_write_overwrite(self):
        path = new_temp_file()

        bm = BlockMatrix.from_numpy(np.array([[0]]))
        bm.write(path)
        self.assertRaises(FatalError, lambda: bm.write(path))

        bm2 = BlockMatrix.from_numpy(np.array([[1]]))
        bm2.write(path, overwrite=True)
        self._assert_eq(BlockMatrix.read(path), bm2)

    def test_stage_locally(self):
        nd = np.arange(0, 80, dtype=float).reshape(8, 10)
        bm_uri = new_temp_file()
        BlockMatrix.from_numpy(nd, block_size=3).write(bm_uri, stage_locally=True)

        bm = BlockMatrix.read(bm_uri)
        self._assert_eq(nd, bm)

    @skip_unless_spark_backend()
    def test_svd(self):
        def assert_same_columns_up_to_sign(a, b):
            for j in range(a.shape[1]):
                assert np.allclose(a[:, j], b[:, j]) or np.allclose(-a[:, j], b[:, j])

        x0 = np.array([[-2.0, 0.0, 3.0],
                       [-1.0, 2.0, 4.0]])
        u0, s0, vt0 = np.linalg.svd(x0, full_matrices=False)

        x = BlockMatrix.from_numpy(x0)

        # _svd
        u, s, vt = x.svd()
        assert_same_columns_up_to_sign(u, u0)
        assert np.allclose(s, s0)
        assert_same_columns_up_to_sign(vt.T, vt0.T)

        s = x.svd(compute_uv=False)
        assert np.allclose(s, s0)

        # left _svd_gramian
        u, s, vt = x.svd(complexity_bound=0)
        assert_same_columns_up_to_sign(u, u0)
        assert np.allclose(s, s0)
        assert_same_columns_up_to_sign(vt.to_numpy().T, vt0.T)

        s = x.svd(compute_uv=False, complexity_bound=0)
        assert np.allclose(s, s0)

        # right _svd_gramian
        x = BlockMatrix.from_numpy(x0.T)
        u, s, vt = x.svd(complexity_bound=0)
        assert_same_columns_up_to_sign(u.to_numpy(), vt0.T)
        assert np.allclose(s, s0)
        assert_same_columns_up_to_sign(vt.T, u0)

        s = x.svd(compute_uv=False, complexity_bound=0)
        assert np.allclose(s, s0)

        # left _svd_gramian when dimensions agree
        x = BlockMatrix.from_numpy(x0[:, :2])
        u, s, vt = x.svd(complexity_bound=0)
        assert isinstance(u, np.ndarray)
        assert isinstance(vt, BlockMatrix)

        # rank-deficient X sets negative eigenvalues to 0.0
        a = np.array([[0.0, 1.0, np.e, np.pi, 10.0, 25.0]])
        x0 = a.T @ a  # rank 1
        e, _ = np.linalg.eigh(x0 @ x0.T)

        x = BlockMatrix.from_numpy(x0)
        _, s, _ = x.svd(complexity_bound=0)
        assert np.all(s >= 0.0)

        s = x.svd(compute_uv=False, complexity_bound=0)
        assert np.all(s >= 0)


    @skip_unless_spark_backend()
    def test_filtering(self):
        np_square = np.arange(16, dtype=np.float64).reshape((4, 4))
        bm = BlockMatrix.from_numpy(np_square)
        assert np.array_equal(bm.filter([3], [3]).to_numpy(), np.array([[15]]))
        assert np.array_equal(bm.filter_rows([3]).filter_cols([3]).to_numpy(), np.array([[15]]))
        assert np.array_equal(bm.filter_cols([3]).filter_rows([3]).to_numpy(), np.array([[15]]))
        assert np.array_equal(bm.filter_rows([2]).filter_rows([0]).to_numpy(), np_square[2:3, :])
        assert np.array_equal(bm.filter_cols([2]).filter_cols([0]).to_numpy(), np_square[:, 2:3])

        with pytest.raises(ValueError) as exc:
            bm.filter_cols([0]).filter_cols([3]).to_numpy()
        assert "index" in str(exc)

        with pytest.raises(ValueError) as exc:
            bm.filter_rows([0]).filter_rows([3]).to_numpy()
        assert "index" in str(exc)


    @skip_unless_spark_backend()
    def test_sparsify_blocks(self):
        block_list = [1, 2]
        np_square = np.arange(16, dtype=np.float64).reshape((4, 4))
        block_size = 2
        bm = BlockMatrix.from_numpy(np_square, block_size=block_size)
        bm = bm._sparsify_blocks(block_list)
        sparse_numpy = sparsify_numpy(np_square, block_size, block_list)
        assert np.array_equal(bm.to_numpy(), sparse_numpy)
        assert np.array_equal(
            sparse_numpy,
            np.array([[0,  0,  2, 3],
                      [0,  0,  6, 7],
                      [8,  9,  0, 0],
                      [12, 13, 0, 0]]))

        block_list = [4, 8, 10, 12, 13, 14]
        np_square = np.arange(225, dtype=np.float64).reshape((15, 15))
        block_size = 4
        bm = BlockMatrix.from_numpy(np_square, block_size=block_size)
        bm = bm._sparsify_blocks(block_list)
        sparse_numpy = sparsify_numpy(np_square, block_size, block_list)
        assert np.array_equal(bm.to_numpy(), sparse_numpy)

    @skip_unless_spark_backend()
    def test_sparse_transposition(self):
        block_list = [1, 2]
        np_square = np.arange(16, dtype=np.float64).reshape((4, 4))
        block_size = 2
        bm = BlockMatrix.from_numpy(np_square, block_size=block_size)
        sparse_bm = bm._sparsify_blocks(block_list).T
        sparse_np = sparsify_numpy(np_square, block_size, block_list).T
        assert np.array_equal(sparse_bm.to_numpy(), sparse_np)

        block_list = [4, 8, 10, 12, 13, 14]
        np_square = np.arange(225, dtype=np.float64).reshape((15, 15))
        block_size = 4
        bm = BlockMatrix.from_numpy(np_square, block_size=block_size)
        sparse_bm = bm._sparsify_blocks(block_list).T
        sparse_np = sparsify_numpy(np_square, block_size, block_list).T
        assert np.array_equal(sparse_bm.to_numpy(), sparse_np)

        block_list = [2, 5, 8, 10, 11]
        np_square = np.arange(150, dtype=np.float64).reshape((10, 15))
        block_size = 4
        bm = BlockMatrix.from_numpy(np_square, block_size=block_size)
        sparse_bm = bm._sparsify_blocks(block_list).T
        sparse_np = sparsify_numpy(np_square, block_size, block_list).T
        assert np.array_equal(sparse_bm.to_numpy(), sparse_np)

        block_list = [2, 5, 8, 10, 11]
        np_square = np.arange(165, dtype=np.float64).reshape((15, 11))
        block_size = 4
        bm = BlockMatrix.from_numpy(np_square, block_size=block_size)
        sparse_bm = bm._sparsify_blocks(block_list).T
        sparse_np = sparsify_numpy(np_square, block_size, block_list).T
        assert np.array_equal(sparse_bm.to_numpy(), sparse_np)

package io.github.xefise.golsp.test.model

import io.github.xefise.golsp.test.model.GoTestNodeEvent.Failed
import io.github.xefise.golsp.test.model.GoTestNodeEvent.Finished
import io.github.xefise.golsp.test.model.GoTestNodeEvent.Output
import io.github.xefise.golsp.test.model.GoTestNodeEvent.SuiteStarted
import io.github.xefise.golsp.test.model.GoTestNodeEvent.TestStarted
import io.github.xefise.golsp.test.model.GoTestNodeEventMapper.Companion.PACKAGE_FAILURE
import io.github.xefise.golsp.test.model.GoTestNodeEventMapper.Companion.PACKAGE_FAILURE_MESSAGE
import org.junit.Assert.assertEquals
import org.junit.Test

class GoTestNodeEventMapperTest {
    private val pkg = "example.com/m"

    @Test
    fun `package failing without a failed test gets a failed child with the batch output`() {
        val events = GoTestNodeEventMapper().map(
            listOf(
                GoTestTreeOp.PackageStarted(1, pkg),
                GoTestTreeOp.Output(1, "undefined: x\n", error = true),
                GoTestTreeOp.Failed(1, "", 0),
            ),
        )
        assertEquals(
            listOf(
                SuiteStarted("1", pkg, "gotest://example.com/m"),
                TestStarted("1:package", "1", PACKAGE_FAILURE, "gotest://example.com/m"),
                Output("1:package", "undefined: x\n", error = true),
                Failed("1:package", PACKAGE_FAILURE_MESSAGE, null),
                Finished("1", 0),
            ),
            events,
        )
    }

    @Test
    fun `package with a failed nested test is finished and never failed itself`() {
        val mapper = GoTestNodeEventMapper()
        mapper.map(listOf(GoTestTreeOp.PackageStarted(1, pkg), GoTestTreeOp.TestStarted(2, 1, "TestA", pkg, "TestA")))
        mapper.map(listOf(GoTestTreeOp.TestStarted(3, 2, "sub", pkg, "TestA/sub")))
        assertEquals(listOf(Failed("3", "", 5)), mapper.map(listOf(GoTestTreeOp.Failed(3, "", 5))))
        assertEquals(
            listOf(Failed("2", "", 6), Finished("1", 7)),
            mapper.map(listOf(GoTestTreeOp.Failed(2, "", 6), GoTestTreeOp.Failed(1, "", 7))),
        )
    }

    @Test
    fun `failure of another package does not count for this one`() {
        val mapper = GoTestNodeEventMapper()
        mapper.map(listOf(GoTestTreeOp.PackageStarted(1, pkg), GoTestTreeOp.TestStarted(2, 1, "TestA", pkg, "TestA")))
        mapper.map(listOf(GoTestTreeOp.Failed(2, "", null), GoTestTreeOp.Failed(1, "", null)))
        val other = mapper.map(listOf(GoTestTreeOp.PackageStarted(3, "example.com/o"), GoTestTreeOp.Failed(3, "", null)))
        assertEquals(TestStarted("3:package", "3", PACKAGE_FAILURE, "gotest://example.com/o"), other[1])
    }

    @Test
    fun `skipped package is finished, skipped test is ignored`() {
        val mapper = GoTestNodeEventMapper()
        mapper.map(listOf(GoTestTreeOp.PackageStarted(1, pkg), GoTestTreeOp.TestStarted(2, 1, "TestA", pkg, "TestA")))
        assertEquals(
            listOf(GoTestNodeEvent.Ignored("2"), Finished("1", null)),
            mapper.map(listOf(GoTestTreeOp.Skipped(2), GoTestTreeOp.Skipped(1))),
        )
    }

    @Test
    fun `after exit open nodes get no result and output is kept`() {
        val mapper = GoTestNodeEventMapper()
        mapper.map(listOf(GoTestTreeOp.PackageStarted(1, pkg), GoTestTreeOp.TestStarted(2, 1, "TestA", pkg, "TestA")))
        val events = mapper.mapAfterExit(
            listOf(
                GoTestTreeOp.Failed(2, "The test did not finish", null),
                GoTestTreeOp.Failed(1, "go test exited", null),
                GoTestTreeOp.Output(null, "# build output\n", error = true),
            ),
        )
        assertEquals(listOf(Output(null, "# build output\n", error = true)), events)
    }
}

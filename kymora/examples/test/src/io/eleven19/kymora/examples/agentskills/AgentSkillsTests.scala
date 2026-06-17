package io.eleven19.kymora.examples.agentskills

import io.eleven19.kymora.workflow.*
import io.eleven19.kymora.workflow.testkit.*
import kyo.*
import kyo.test.*

class AgentSkillsTests extends Test[Any]:
  "Skill ids are prefixed by 'agent'" in {
    assert(Skills.summarise.id == TaskId("agent.summarise"))
    assert(Skills.compare.id == TaskId("agent.compare"))
  }
  "summarise skill runs end-to-end" in {
    for
      driver <- WorkflowTestDriver.init
      result <- Env.run(driver.config)(driver.run(Skills.summarise))
    yield assert(result.toString.contains("summary"))
  }
  "compare skill runs end-to-end" in {
    for
      driver <- WorkflowTestDriver.init
      result <- Env.run(driver.config)(driver.run(Skills.compare))
    yield assert(result.toString.contains("compare"))
  }
end AgentSkillsTests

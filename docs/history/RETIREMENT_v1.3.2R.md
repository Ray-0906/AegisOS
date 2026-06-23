Runtime consumers: 0

Test consumers: 0

Residual references:
- Promote.java (deleted)
- DirectPromote.java (deleted)
- ClientCommands.java (deleted)

Legacy concepts removed:
- withClient()
- ephemeral node boot
- raft polling
- leader wait loops
- CLI membership logic

Verification:
mvn clean verify

Result:
PASS

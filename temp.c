void Cfg485(RS485_PORT_CFG_PARAM   *cfg)
{
	int fd;
	int rc;
	struct termios new_opt;
	char deviceName[16];
	RS485BUS   *pRs485 = RS485_ID_TO_NODE_PTR(cfg->comNum);

	INFO("Enter Cfg485..., starting to configure RS485 BUS #%d", cfg->comNum);

	ASSERT_without_errno(!pRs485->initialized);
	
	sprintf(deviceName, "/dev/ttyS%d", cfg->comNum);

	fd = open(deviceName, O_RDWR | O_NOCTTY) ;
	if (fd == -1)  // Don't use ASSERT instead, because this failure may be due to an error in the configuration.
		return;

	INFO("   open 485 success");
	
	memset(&new_opt, 0, sizeof(new_opt));
	rc = tcgetattr(fd, &new_opt);
	ASSERT_with_errno(rc == 0);

	rc = tcflush(fd, TCIOFLUSH);
	ASSERT_with_errno(rc == 0);

	rc = cfsetispeed(&new_opt, ConvertBaudRateToBxxConstant(cfg->baudRate));
	ASSERT_with_errno(rc == 0);

	rc = cfsetospeed(&new_opt, ConvertBaudRateToBxxConstant(cfg->baudRate));
	ASSERT_with_errno(rc == 0);

	new_opt.c_cflag |= CLOCAL;
	new_opt.c_cflag |= CREAD;
	new_opt.c_cflag |= HUPCL;
	new_opt.c_cflag &= ~CRTSCTS;

	new_opt.c_cflag &= ~CSIZE;
	new_opt.c_cflag |= ConvertDataBitsNumToCSxConstant(cfg->dataBitsNum);

	switch (cfg->parity)
	{
		#if 0
		case CFG_PARITY_NONE:
		case CFG_PARITY_MARK:
		case CFG_PARITY_SPACE:
		#else
		default:
		#endif
			new_opt.c_cflag &= ~PARENB;
			new_opt.c_iflag &= ~INPCK;
			break;

		case CFG_PARITY_ODD:
			new_opt.c_cflag |= PARENB | PARODD;
			new_opt.c_iflag |= INPCK;
			break;

		case CFG_PARITY_EVEN:
			new_opt.c_cflag |= PARENB;
			new_opt.c_cflag &= ~PARODD;
			new_opt.c_iflag |= INPCK;
			break;
	}

	if (cfg->stopBitsNum == 2)
		new_opt.c_cflag |= CSTOPB;
	else
		new_opt.c_cflag &= ~CSTOPB;

	new_opt.c_lflag &= ~(ICANON | ECHO | ISIG);
	new_opt.c_oflag &= ~OPOST;

	new_opt.c_cc[VMIN]	= 0;
	new_opt.c_cc[VTIME] = 0; // cfg->timeoutMs / 100;

	rc = tcflush(fd, TCIFLUSH);
	ASSERT_with_errno(rc == 0);

	rc = tcsetattr(fd, TCSANOW, &new_opt);
	ASSERT_with_errno(rc == 0);

	//tcdrain
	//tcgetattr  to ensure set is OK

	rc = pthread_mutex_init(&pRs485->rwLock, NULL);
	ASSERT_with_errno(rc == 0);

	pRs485->fd 		= fd;
	pRs485->guiYue	= cfg->guiYue;
	pRs485->comNum	= cfg->comNum;
	pRs485->wait485responseTimeOutMs = cfg->timeoutMs;
	pRs485->initialized 	= 1;
	strcpy(pRs485->comName,cfg->comName);
	memcpy(&pRs485->comIEDList[0],&cfg->comIEDList[0],sizeof(pRs485->comIEDList));
	pRs485->comIEDCnt =  cfg->comIEDCnt;		

	CreateRs485CmdQueue(pRs485);	
	CreateThreadFor485(pRs485);

	INFO("Leave Cfg485");
}

-- phpMyAdmin SQL Dump
-- version 5.1.0
-- https://www.phpmyadmin.net/
--
-- Host: 192.168.200.20
-- Generation Time: May 20, 2021 at 09:55 AM
-- Server version: 10.5.10-MariaDB-1:10.5.10+maria~focal
-- PHP Version: 7.4.19

SET SQL_MODE = "NO_AUTO_VALUE_ON_ZERO";
START TRANSACTION;
SET time_zone = "+00:00";


/*!40101 SET @OLD_CHARACTER_SET_CLIENT=@@CHARACTER_SET_CLIENT */;
/*!40101 SET @OLD_CHARACTER_SET_RESULTS=@@CHARACTER_SET_RESULTS */;
/*!40101 SET @OLD_COLLATION_CONNECTION=@@COLLATION_CONNECTION */;
/*!40101 SET NAMES utf8mb4 */;

--
-- Database: `sat`
--
CREATE DATABASE IF NOT EXISTS `sat` DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci;
USE `sat`;

-- --------------------------------------------------------

--
-- Table structure for table `actionlog`
--

CREATE TABLE IF NOT EXISTS `actionlog` (
  `actionid` int(11) NOT NULL AUTO_INCREMENT,
  `dateline` bigint(20) NOT NULL,
  `userid` char(36) CHARACTER SET ascii COLLATE ascii_bin DEFAULT NULL,
  `targetid` char(36) CHARACTER SET ascii COLLATE ascii_bin DEFAULT NULL,
  `description` varchar(500) COLLATE utf8mb4_unicode_ci NOT NULL,
  PRIMARY KEY (`actionid`),
  KEY `userid` (`userid`,`dateline`),
  KEY `targetid` (`targetid`,`dateline`),
  KEY `dateline` (`dateline`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- --------------------------------------------------------

--
-- Table structure for table `configuration`
--

CREATE TABLE IF NOT EXISTS `configuration` (
  `parameter` varchar(100) CHARACTER SET ascii NOT NULL,
  `value` blob NOT NULL,
  PRIMARY KEY (`parameter`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- --------------------------------------------------------

--
-- Table structure for table `imagebase`
--

CREATE TABLE IF NOT EXISTS `imagebase` (
  `imagebaseid` char(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  `latestversionid` char(36) CHARACTER SET ascii COLLATE ascii_bin DEFAULT NULL,
  `displayname` varchar(100) COLLATE utf8mb4_unicode_ci NOT NULL,
  `description` text COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `osid` int(11) DEFAULT NULL,
  `virtid` varchar(10) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `createtime` bigint(20) NOT NULL,
  `updatetime` bigint(20) NOT NULL,
  `ownerid` char(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  `updaterid` char(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  `sharemode` enum('LOCAL','PUBLISH','DOWNLOAD','FROZEN') COLLATE utf8mb4_unicode_ci NOT NULL,
  `istemplate` tinyint(1) NOT NULL,
  `canlinkdefault` tinyint(1) NOT NULL,
  `candownloaddefault` tinyint(1) NOT NULL,
  `caneditdefault` tinyint(1) NOT NULL,
  `canadmindefault` tinyint(1) NOT NULL,
  PRIMARY KEY (`imagebaseid`),
  KEY `owner` (`ownerid`),
  KEY `fk_imagebase_1_idx` (`osid`),
  KEY `fk_imagebase_owner_idx` (`updaterid`),
  KEY `fk_imagebase_1_idx1` (`virtid`),
  KEY `latestversion_idx` (`latestversionid`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- --------------------------------------------------------

--
-- Table structure for table `imageblock`
--

CREATE TABLE IF NOT EXISTS `imageblock` (
  `imageversionid` char(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  `startbyte` bigint(20) NOT NULL,
  `blocksize` int(11) NOT NULL,
  `blocksha1` binary(20) DEFAULT NULL,
  `ismissing` tinyint(1) NOT NULL COMMENT 'true if this block is missing from the file, either because it was not transferred to the server yet, or because it failed an integrity check.',
  PRIMARY KEY (`imageversionid`,`startbyte`,`blocksize`),
  KEY `checksums` (`blocksha1`,`blocksize`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- --------------------------------------------------------

--
-- Table structure for table `imagepermission`
--

CREATE TABLE IF NOT EXISTS `imagepermission` (
  `imagebaseid` char(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  `userid` char(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  `canlink` tinyint(1) NOT NULL,
  `candownload` tinyint(1) NOT NULL,
  `canedit` tinyint(1) NOT NULL,
  `canadmin` tinyint(1) NOT NULL,
  PRIMARY KEY (`imagebaseid`,`userid`),
  KEY `fk_imagepermission_2_idx` (`userid`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- --------------------------------------------------------

--
-- Table structure for table `imagetag`
--

CREATE TABLE IF NOT EXISTS `imagetag` (
  `imagebaseid` char(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  `tagname` char(32) COLLATE utf8mb4_unicode_ci NOT NULL,
  PRIMARY KEY (`imagebaseid`,`tagname`),
  KEY `tag_image` (`tagname`,`imagebaseid`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- --------------------------------------------------------

--
-- Table structure for table `imageversion`
--

CREATE TABLE IF NOT EXISTS `imageversion` (
  `imageversionid` char(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  `imagebaseid` char(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  `createtime` bigint(20) NOT NULL,
  `expiretime` bigint(20) NOT NULL,
  `filesize` bigint(20) NOT NULL,
  `filepath` varchar(200) COLLATE utf8mb4_unicode_ci NOT NULL,
  `uploaderid` char(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  `isrestricted` tinyint(1) NOT NULL,
  `isvalid` tinyint(1) NOT NULL,
  `isprocessed` tinyint(1) NOT NULL,
  `mastersha1` binary(20) DEFAULT NULL,
  `virtualizerconfig` blob DEFAULT NULL COMMENT 'Specific configuration of the virtualizer for this image. For vmware, this is basically a dump of the *.vmx.',
  `deletestate` enum('KEEP','SHOULD_DELETE','WANT_DELETE') CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT 'KEEP',
  PRIMARY KEY (`imageversionid`),
  KEY `version_access` (`imagebaseid`,`isvalid`,`createtime`),
  KEY `fk_imageversion_2_idx` (`uploaderid`),
  KEY `expire_index` (`expiretime`),
  KEY `deletestate` (`deletestate`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- --------------------------------------------------------

--
-- Table structure for table `imageversion_x_software`
--

CREATE TABLE IF NOT EXISTS `imageversion_x_software` (
  `imageversionid` char(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  `softwareid` int(11) NOT NULL,
  PRIMARY KEY (`imageversionid`,`softwareid`),
  KEY `fk_imageversion_x_software_2_idx` (`softwareid`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- --------------------------------------------------------

--
-- Table structure for table `lecture`
--

CREATE TABLE IF NOT EXISTS `lecture` (
  `lectureid` char(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  `displayname` varchar(100) COLLATE utf8mb4_unicode_ci NOT NULL,
  `description` text COLLATE utf8mb4_unicode_ci NOT NULL,
  `imageversionid` char(36) CHARACTER SET ascii COLLATE ascii_bin DEFAULT NULL COMMENT 'We reference a specific image version here, not the base image.\nOn update of an image, we update the lecture table for all matching lectures that used the current image version.\nThis way, a tutor can explicitly switch back to an older version of an image.',
  `autoupdate` tinyint(1) UNSIGNED NOT NULL DEFAULT 1,
  `isenabled` tinyint(1) UNSIGNED NOT NULL DEFAULT 1,
  `isprivate` tinyint(1) UNSIGNED NOT NULL DEFAULT 0 COMMENT 'Only users from the lectureuser table can start this lecture',
  `islocationprivate` tinyint(1) UNSIGNED NOT NULL DEFAULT 0,
  `starttime` bigint(20) NOT NULL,
  `endtime` bigint(20) NOT NULL,
  `lastused` bigint(20) NOT NULL DEFAULT 0,
  `usecount` int(11) NOT NULL DEFAULT 0,
  `createtime` bigint(20) NOT NULL,
  `updatetime` bigint(20) NOT NULL,
  `ownerid` char(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  `updaterid` char(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  `runscript` text COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `nics` varchar(200) CHARACTER SET ascii COLLATE ascii_bin DEFAULT NULL COMMENT 'Freeform text field for future extendability. Format is specified at application layer.',
  `netrules` text COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT 'user defined firewall rules, applied at the linux base system.',
  `isexam` tinyint(1) NOT NULL,
  `iswhitelistonly` tinyint(1) NOT NULL DEFAULT 0,
  `hasinternetaccess` tinyint(1) NOT NULL,
  `hasusbaccess` tinyint(1) NOT NULL,
  `caneditdefault` tinyint(1) NOT NULL,
  `canadmindefault` tinyint(1) NOT NULL,
  PRIMARY KEY (`lectureid`),
  KEY `fk_lecture_1_idx` (`imageversionid`),
  KEY `fk_lecture_2_idx` (`ownerid`),
  KEY `fk_lecture_3_idx` (`updaterid`),
  KEY `list_lookup` (`isenabled`,`isexam`,`isprivate`,`endtime`,`starttime`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- --------------------------------------------------------

--
-- Table structure for table `lecturefilter`
--

CREATE TABLE IF NOT EXISTS `lecturefilter` (
  `lectureid` char(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  `filterpresetid` int(11) DEFAULT NULL,
  `filtertype` varchar(24) CHARACTER SET ascii DEFAULT NULL,
  `filterkey` varchar(24) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `filtervalue` varchar(200) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  KEY `lectureid` (`lectureid`,`filtertype`),
  KEY `filterpresetid` (`filterpresetid`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- --------------------------------------------------------

--
-- Table structure for table `lecturepermission`
--

CREATE TABLE IF NOT EXISTS `lecturepermission` (
  `lectureid` char(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  `userid` char(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  `canedit` tinyint(1) NOT NULL,
  `canadmin` tinyint(1) NOT NULL,
  PRIMARY KEY (`lectureid`,`userid`),
  KEY `fk_lecturepermission_2_idx` (`userid`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- --------------------------------------------------------

--
-- Table structure for table `lectureuser`
--

CREATE TABLE IF NOT EXISTS `lectureuser` (
  `lectureid` char(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  `userlogin` varchar(45) COLLATE utf8mb4_unicode_ci NOT NULL,
  PRIMARY KEY (`lectureid`,`userlogin`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- --------------------------------------------------------

--
-- Table structure for table `lecture_x_location`
--

CREATE TABLE IF NOT EXISTS `lecture_x_location` (
  `lectureid` char(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  `locationid` int(11) NOT NULL,
  PRIMARY KEY (`lectureid`,`locationid`),
  KEY `locationid` (`locationid`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- --------------------------------------------------------

--
-- Table structure for table `lecture_x_networkrule`
--

CREATE TABLE IF NOT EXISTS `lecture_x_networkrule` (
  `lectureid` char(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  `ruleid` int(11) NOT NULL,
  PRIMARY KEY (`lectureid`,`ruleid`),
  KEY `ruleid` (`ruleid`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- --------------------------------------------------------

--
-- Table structure for table `lecture_x_runscript`
--

CREATE TABLE IF NOT EXISTS `lecture_x_runscript` (
  `lectureid` char(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  `runscriptid` int(11) NOT NULL,
  PRIMARY KEY (`lectureid`,`runscriptid`),
  KEY `runscriptid` (`runscriptid`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- --------------------------------------------------------

--
-- Table structure for table `mailqueue`
--

CREATE TABLE IF NOT EXISTS `mailqueue` (
  `mailid` char(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  `userid` char(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  `message` text NOT NULL,
  `failcount` int(11) NOT NULL DEFAULT 0,
  `dateline` bigint(20) NOT NULL,
  PRIMARY KEY (`mailid`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- --------------------------------------------------------

--
-- Table structure for table `networkshare`
--

CREATE TABLE IF NOT EXISTS `networkshare` (
  `lectureid` char(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  `sharepresetid` int(11) DEFAULT NULL,
  `sharedata` varchar(500) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  KEY `sharepresetid` (`sharepresetid`),
  KEY `fk_lectureid_1` (`lectureid`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- --------------------------------------------------------

--
-- Table structure for table `operatingsystem`
--

CREATE TABLE IF NOT EXISTS `operatingsystem` (
  `osid` int(11) NOT NULL COMMENT 'Defined on the master server, so no auto_increment!',
  `displayname` varchar(100) COLLATE utf8mb4_unicode_ci NOT NULL,
  `architecture` varchar(14) COLLATE utf8mb4_unicode_ci NOT NULL,
  `maxmem` int(11) NOT NULL,
  `maxcpu` int(11) NOT NULL,
  PRIMARY KEY (`osid`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- --------------------------------------------------------

--
-- Table structure for table `organization`
--

CREATE TABLE IF NOT EXISTS `organization` (
  `organizationid` char(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  `displayname` varchar(255) COLLATE utf8mb4_unicode_ci NOT NULL,
  `canlogin` tinyint(1) NOT NULL DEFAULT 0,
  PRIMARY KEY (`organizationid`),
  KEY `loginkey` (`canlogin`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- --------------------------------------------------------

--
-- Table structure for table `os_x_virt`
--

CREATE TABLE IF NOT EXISTS `os_x_virt` (
  `osid` int(11) NOT NULL,
  `virtid` varchar(10) COLLATE utf8mb4_unicode_ci NOT NULL,
  `virtoskeyword` varchar(30) COLLATE utf8mb4_unicode_ci NOT NULL,
  PRIMARY KEY (`osid`,`virtid`),
  KEY `fk_os_x_virt_2_idx` (`virtid`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- --------------------------------------------------------

--
-- Table structure for table `presetlecturefilter`
--

CREATE TABLE IF NOT EXISTS `presetlecturefilter` (
  `filterid` int(11) NOT NULL AUTO_INCREMENT,
  `filtertype` varchar(24) CHARACTER SET ascii NOT NULL,
  `filtername` varchar(100) COLLATE utf8mb4_unicode_ci NOT NULL,
  `filterkey` varchar(24) COLLATE utf8mb4_unicode_ci NOT NULL,
  `filtervalue` varchar(200) COLLATE utf8mb4_unicode_ci NOT NULL,
  PRIMARY KEY (`filterid`),
  KEY `filtertype` (`filtertype`,`filtername`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- --------------------------------------------------------

--
-- Table structure for table `presetnetworkrule`
--

CREATE TABLE IF NOT EXISTS `presetnetworkrule` (
  `ruleid` int(11) NOT NULL AUTO_INCREMENT,
  `rulename` varchar(100) COLLATE utf8mb4_unicode_ci NOT NULL,
  `ruledata` text COLLATE utf8mb4_unicode_ci NOT NULL,
  PRIMARY KEY (`ruleid`),
  KEY `rulename` (`rulename`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- --------------------------------------------------------

--
-- Table structure for table `presetnetworkshare`
--

CREATE TABLE IF NOT EXISTS `presetnetworkshare` (
  `shareid` int(11) NOT NULL AUTO_INCREMENT,
  `sharename` varchar(100) COLLATE utf8mb4_unicode_ci NOT NULL,
  `sharedata` varchar(500) COLLATE utf8mb4_unicode_ci NOT NULL,
  `active` tinyint(1) NOT NULL DEFAULT 0,
  PRIMARY KEY (`shareid`),
  KEY `sharename` (`sharename`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- --------------------------------------------------------

--
-- Table structure for table `presetrunscript`
--

CREATE TABLE IF NOT EXISTS `presetrunscript` (
  `runscriptid` int(11) NOT NULL AUTO_INCREMENT,
  `scriptname` varchar(100) COLLATE utf8mb4_unicode_ci NOT NULL,
  `content` text COLLATE utf8mb4_unicode_ci NOT NULL,
  `extension` varchar(10) COLLATE utf8mb4_unicode_ci NOT NULL,
  `visibility` tinyint(1) NOT NULL COMMENT '0 = hidden, 1 = normal, 2 = minimized',
  `passcreds` tinyint(1) NOT NULL,
  `isglobal` tinyint(1) NOT NULL COMMENT 'Whether to apply this script to all lectures',
  PRIMARY KEY (`runscriptid`),
  KEY `isglobal` (`isglobal`),
  KEY `scriptname` (`scriptname`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- --------------------------------------------------------

--
-- Table structure for table `presetrunscript_x_operatingsystem`
--

CREATE TABLE IF NOT EXISTS `presetrunscript_x_operatingsystem` (
  `runscriptid` int(11) NOT NULL,
  `osid` int(11) NOT NULL,
  PRIMARY KEY (`runscriptid`,`osid`),
  KEY `osid` (`osid`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- --------------------------------------------------------

--
-- Table structure for table `software`
--

CREATE TABLE IF NOT EXISTS `software` (
  `softwareid` int(11) NOT NULL AUTO_INCREMENT COMMENT 'This ID is used internally only, it never leaves the satellite.',
  `softwarestring` varchar(120) COLLATE utf8mb4_unicode_ci NOT NULL,
  `isrestricted` tinyint(1) NOT NULL,
  `isrestrictedoverride` tinyint(1) DEFAULT NULL,
  PRIMARY KEY (`softwareid`),
  UNIQUE KEY `softwarestring_UNIQUE` (`softwarestring`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- --------------------------------------------------------

--
-- Table structure for table `software_x_tag`
--

CREATE TABLE IF NOT EXISTS `software_x_tag` (
  `softwareid` int(11) NOT NULL,
  `tagname` char(32) COLLATE utf8mb4_unicode_ci NOT NULL,
  PRIMARY KEY (`softwareid`,`tagname`),
  KEY `fk_software_x_tag_2_idx` (`tagname`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- --------------------------------------------------------

--
-- Table structure for table `user`
--

CREATE TABLE IF NOT EXISTS `user` (
  `userid` char(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  `firstname` varchar(50) COLLATE utf8mb4_unicode_ci NOT NULL,
  `lastname` varchar(50) COLLATE utf8mb4_unicode_ci NOT NULL,
  `email` varchar(100) COLLATE utf8mb4_unicode_ci NOT NULL,
  `organizationid` char(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  `lastlogin` bigint(20) DEFAULT NULL,
  `canlogin` tinyint(1) NOT NULL DEFAULT 0,
  `issuperuser` tinyint(1) NOT NULL DEFAULT 0,
  `emailnotifications` tinyint(1) NOT NULL DEFAULT 1,
  PRIMARY KEY (`userid`),
  KEY `fk_user_1_idx` (`organizationid`),
  KEY `mail_idx` (`email`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- --------------------------------------------------------

--
-- Table structure for table `virtualizer`
--

CREATE TABLE IF NOT EXISTS `virtualizer` (
  `virtid` varchar(10) COLLATE utf8mb4_unicode_ci NOT NULL,
  `virtname` varchar(20) COLLATE utf8mb4_unicode_ci NOT NULL,
  PRIMARY KEY (`virtid`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

--
-- Constraints for dumped tables
--

--
-- Constraints for table `actionlog`
--
ALTER TABLE `actionlog`
  ADD CONSTRAINT `actionlog_ibfk_1` FOREIGN KEY (`userid`) REFERENCES `user` (`userid`) ON DELETE SET NULL ON UPDATE CASCADE;

--
-- Constraints for table `imagebase`
--
ALTER TABLE `imagebase`
  ADD CONSTRAINT `fk_imagebase_1` FOREIGN KEY (`virtid`) REFERENCES `virtualizer` (`virtid`) ON UPDATE CASCADE,
  ADD CONSTRAINT `fk_imagebase_os` FOREIGN KEY (`osid`) REFERENCES `operatingsystem` (`osid`),
  ADD CONSTRAINT `fk_imagebase_owner` FOREIGN KEY (`ownerid`) REFERENCES `user` (`userid`) ON UPDATE CASCADE,
  ADD CONSTRAINT `fk_imagebase_updater` FOREIGN KEY (`updaterid`) REFERENCES `user` (`userid`) ON UPDATE CASCADE,
  ADD CONSTRAINT `latestversion` FOREIGN KEY (`latestversionid`) REFERENCES `imageversion` (`imageversionid`) ON UPDATE CASCADE;

--
-- Constraints for table `imageblock`
--
ALTER TABLE `imageblock`
  ADD CONSTRAINT `fk_imageblocksha1_1` FOREIGN KEY (`imageversionid`) REFERENCES `imageversion` (`imageversionid`) ON DELETE CASCADE ON UPDATE CASCADE;

--
-- Constraints for table `imagepermission`
--
ALTER TABLE `imagepermission`
  ADD CONSTRAINT `fk_imagepermission_1` FOREIGN KEY (`imagebaseid`) REFERENCES `imagebase` (`imagebaseid`) ON DELETE CASCADE ON UPDATE CASCADE,
  ADD CONSTRAINT `fk_imagepermission_2` FOREIGN KEY (`userid`) REFERENCES `user` (`userid`) ON DELETE CASCADE ON UPDATE CASCADE;

--
-- Constraints for table `imagetag`
--
ALTER TABLE `imagetag`
  ADD CONSTRAINT `fk_imagetag_1` FOREIGN KEY (`imagebaseid`) REFERENCES `imagebase` (`imagebaseid`) ON DELETE CASCADE ON UPDATE CASCADE;

--
-- Constraints for table `imageversion`
--
ALTER TABLE `imageversion`
  ADD CONSTRAINT `fk_imageversion_base` FOREIGN KEY (`imagebaseid`) REFERENCES `imagebase` (`imagebaseid`) ON UPDATE CASCADE,
  ADD CONSTRAINT `fk_imageversion_creator` FOREIGN KEY (`uploaderid`) REFERENCES `user` (`userid`) ON UPDATE CASCADE;

--
-- Constraints for table `imageversion_x_software`
--
ALTER TABLE `imageversion_x_software`
  ADD CONSTRAINT `fk_imageversion_x_software_1` FOREIGN KEY (`imageversionid`) REFERENCES `imageversion` (`imageversionid`) ON DELETE CASCADE ON UPDATE CASCADE,
  ADD CONSTRAINT `fk_imageversion_x_software_2` FOREIGN KEY (`softwareid`) REFERENCES `software` (`softwareid`) ON DELETE CASCADE ON UPDATE CASCADE;

--
-- Constraints for table `lecture`
--
ALTER TABLE `lecture`
  ADD CONSTRAINT `fk_lecture_image` FOREIGN KEY (`imageversionid`) REFERENCES `imageversion` (`imageversionid`) ON UPDATE CASCADE,
  ADD CONSTRAINT `fk_lecture_owner` FOREIGN KEY (`ownerid`) REFERENCES `user` (`userid`) ON UPDATE CASCADE,
  ADD CONSTRAINT `fk_lecture_updater` FOREIGN KEY (`updaterid`) REFERENCES `user` (`userid`) ON UPDATE CASCADE;

--
-- Constraints for table `lecturefilter`
--
ALTER TABLE `lecturefilter`
  ADD CONSTRAINT `filterpresetid` FOREIGN KEY (`filterpresetid`) REFERENCES `presetlecturefilter` (`filterid`) ON DELETE CASCADE ON UPDATE CASCADE,
  ADD CONSTRAINT `lectureid` FOREIGN KEY (`lectureid`) REFERENCES `lecture` (`lectureid`) ON DELETE CASCADE ON UPDATE CASCADE;

--
-- Constraints for table `lecturepermission`
--
ALTER TABLE `lecturepermission`
  ADD CONSTRAINT `fk_lecturepermission_1` FOREIGN KEY (`lectureid`) REFERENCES `lecture` (`lectureid`) ON DELETE CASCADE ON UPDATE CASCADE,
  ADD CONSTRAINT `fk_lecturepermission_2` FOREIGN KEY (`userid`) REFERENCES `user` (`userid`) ON DELETE CASCADE ON UPDATE CASCADE;

--
-- Constraints for table `lectureuser`
--
ALTER TABLE `lectureuser`
  ADD CONSTRAINT `fk_lectureuser_1` FOREIGN KEY (`lectureid`) REFERENCES `lecture` (`lectureid`) ON DELETE CASCADE ON UPDATE CASCADE;

--
-- Constraints for table `lecture_x_location`
--
ALTER TABLE `lecture_x_location`
  ADD CONSTRAINT `lecture_x_location_ibfk_1` FOREIGN KEY (`lectureid`) REFERENCES `lecture` (`lectureid`) ON DELETE CASCADE ON UPDATE CASCADE;

--
-- Constraints for table `lecture_x_networkrule`
--
ALTER TABLE `lecture_x_networkrule`
  ADD CONSTRAINT `lecture_x_networkrule_ibfk_1` FOREIGN KEY (`lectureid`) REFERENCES `lecture` (`lectureid`) ON DELETE CASCADE ON UPDATE CASCADE,
  ADD CONSTRAINT `lecture_x_networkrule_ibfk_2` FOREIGN KEY (`ruleid`) REFERENCES `presetnetworkrule` (`ruleid`) ON DELETE CASCADE ON UPDATE CASCADE;

--
-- Constraints for table `lecture_x_runscript`
--
ALTER TABLE `lecture_x_runscript`
  ADD CONSTRAINT `lecture_runscript` FOREIGN KEY (`lectureid`) REFERENCES `lecture` (`lectureid`) ON DELETE CASCADE ON UPDATE CASCADE,
  ADD CONSTRAINT `lecture_x_runscript_ibfk_1` FOREIGN KEY (`runscriptid`) REFERENCES `presetrunscript` (`runscriptid`) ON DELETE CASCADE ON UPDATE CASCADE;

--
-- Constraints for table `networkshare`
--
ALTER TABLE `networkshare`
  ADD CONSTRAINT `fk_lectureid_1` FOREIGN KEY (`lectureid`) REFERENCES `lecture` (`lectureid`) ON DELETE CASCADE ON UPDATE CASCADE;

--
-- Constraints for table `os_x_virt`
--
ALTER TABLE `os_x_virt`
  ADD CONSTRAINT `fk_os_x_virt_1` FOREIGN KEY (`osid`) REFERENCES `operatingsystem` (`osid`) ON DELETE CASCADE ON UPDATE CASCADE,
  ADD CONSTRAINT `fk_os_x_virt_2` FOREIGN KEY (`virtid`) REFERENCES `virtualizer` (`virtid`) ON DELETE CASCADE ON UPDATE CASCADE;

--
-- Constraints for table `presetrunscript_x_operatingsystem`
--
ALTER TABLE `presetrunscript_x_operatingsystem`
  ADD CONSTRAINT `osid` FOREIGN KEY (`osid`) REFERENCES `operatingsystem` (`osid`) ON DELETE CASCADE ON UPDATE CASCADE,
  ADD CONSTRAINT `runscriptid` FOREIGN KEY (`runscriptid`) REFERENCES `presetrunscript` (`runscriptid`) ON DELETE CASCADE ON UPDATE CASCADE;

--
-- Constraints for table `software_x_tag`
--
ALTER TABLE `software_x_tag`
  ADD CONSTRAINT `fk_software_x_tag_1` FOREIGN KEY (`softwareid`) REFERENCES `software` (`softwareid`) ON DELETE CASCADE ON UPDATE CASCADE;

--
-- Constraints for table `user`
--
ALTER TABLE `user`
  ADD CONSTRAINT `fk_user_1` FOREIGN KEY (`organizationid`) REFERENCES `organization` (`organizationid`) ON UPDATE CASCADE;
COMMIT;

/*!40101 SET CHARACTER_SET_CLIENT=@OLD_CHARACTER_SET_CLIENT */;
/*!40101 SET CHARACTER_SET_RESULTS=@OLD_CHARACTER_SET_RESULTS */;
/*!40101 SET COLLATION_CONNECTION=@OLD_COLLATION_CONNECTION */;
